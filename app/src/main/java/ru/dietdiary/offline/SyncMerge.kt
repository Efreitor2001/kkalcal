package ru.dietdiary.offline

import org.json.JSONArray
import org.json.JSONObject

/** Hybrid logical clock: wall time, logical counter, stable installation ID. */
internal data class SyncStamp(val millis: Long, val counter: Long, val device: String) : Comparable<SyncStamp> {
    override fun compareTo(other: SyncStamp): Int = compareValuesBy(this, other,
        SyncStamp::millis, SyncStamp::counter, SyncStamp::device)

    companion object { val ZERO = SyncStamp(0, 0, "seed") }
}

/** A null payload is reserved for a tombstone; clearing a field uses the JSON value null. */
internal data class SyncRecord(val stamp: SyncStamp, val deleted: Boolean = false, val value: String? = null)
internal data class SyncState(val records: Map<String, SyncRecord>, val clock: SyncStamp = SyncStamp.ZERO)
internal data class SyncDocument(val data: AppData, val state: SyncState)

/**
 * State-based LWW map. Records and tombstones are never discarded, so replaying an old Drive
 * snapshot cannot revive a removed item. Each daily indicator and goal is an independent key.
 * Identical stamp conflicts use a deterministic payload tie-break rather than arrival order.
 */
internal object SyncMerge {
    val logFields = setOf("weight", "waist", "steps", "sleep", "note", "bodyFat", "training", "calories", "protein")
    private val goalFields = setOf("kcal", "protein", "fat", "carbs", "weight")
    private val portableSettings = setOf("theme", "startTab", "weekStartsOn", AD_FREE_RECEIPT_SETTING)
    private const val MAX_RECORDS = 600_000
    private const val MAX_MILLIS = 253_402_300_799_999L // Last millisecond of year 9999.

    fun next(clock: SyncStamp, device: String, now: Long): SyncStamp {
        require(device.isNotBlank()) { tr(R.string.data_device_id_missing) }
        val wall = maxOf(now.coerceIn(1, MAX_MILLIS), clock.millis)
        val counter = if (wall == clock.millis) Math.addExact(clock.counter, 1) else 0
        require(counter < Long.MAX_VALUE) { tr(R.string.data_logical_clock_exhausted) }
        return SyncStamp(wall, counter, device)
    }

    fun merge(a: SyncState, b: SyncState): SyncState {
        val records = a.records.toMutableMap()
        b.records.forEach { (key, incoming) ->
            val current = records[key]
            if (current == null || compare(incoming, current) > 0) records[key] = incoming
        }
        require(records.size <= MAX_RECORDS) { tr(R.string.data_too_many_sync_records) }
        return SyncState(records.toSortedMap(), maxOf(a.clock, b.clock))
    }

    private fun compare(a: SyncRecord, b: SyncRecord): Int {
        val stamps = a.stamp.compareTo(b.stamp)
        if (stamps != 0) return stamps
        if (a.deleted != b.deleted) return if (a.deleted) 1 else -1
        return (a.value ?: "").compareTo(b.value ?: "")
    }

    /** Migration starts unchanged catalog defaults at zero; user records get a real stamp. */
    fun initial(data: AppData, catalog: List<Product>, device: String, now: Long): SyncState {
        val stamp = next(SyncStamp.ZERO, device, now)
        val flat = flatten(data)
        val seed = flatten(AppData(catalog))
        val records = flat.mapValues { (key, value) ->
            val unchanged = key.startsWith("p/") && value == seed[key]
            SyncRecord(if (unchanged || isDefaultGoal(key, value)) SyncStamp.ZERO else stamp, value = value)
        }.toMutableMap()
        catalog.filter { seedProduct -> data.products.none { it.id == seedProduct.id } }.forEach {
            records["p/${it.id}"] = SyncRecord(stamp, deleted = true)
        }
        return SyncState(records.toSortedMap(), records.values.maxOfOrNull { it.stamp } ?: SyncStamp.ZERO)
    }

    /** Only changed fields get a new stamp. A manual replacement stamps every imported value. */
    fun change(before: AppData, after: AppData, state: SyncState, device: String, now: Long,
        replaceAll: Boolean = false): SyncState {
        DataJson.validate(after)
        val old = flatten(before)
        val fresh = flatten(after)
        val changed = fresh.filter { (key, value) -> replaceAll || old[key] != value }
        val removed = old.keys - fresh.keys
        if (changed.isEmpty() && removed.isEmpty() && !replaceAll) return state
        val stamp = next(state.clock, device, now)
        val result = state.records.toMutableMap()
        changed.forEach { (key, value) -> result[key] = SyncRecord(stamp, value = value) }
        // A replacing import also tombstones records known only through previous tombstones/merges.
        val absent = if (replaceAll) (state.records.keys + old.keys).filter { !it.startsWith("l/") && it !in fresh } else removed
        absent.filterNot { it.startsWith("l/") }.forEach { result[it] = SyncRecord(stamp, deleted = true) }
        val removedDates = before.logs.map { it.date }.toSet() - after.logs.map { it.date }.toSet()
        val allKnownDates = state.records.keys.filter { it.startsWith("l/") }.map { it.substring(2, 12) }.toSet()
        val deletedDates = if (replaceAll) allKnownDates - after.logs.map { it.date }.toSet() else removedDates
        deletedDates.forEach { date -> result["l/$date/@deleted"] = SyncRecord(stamp, deleted = true) }
        // A cleared indicator on a surviving row is a value-null register, not row deletion.
        removed.filter { it.startsWith("l/") && it.substring(2, 12) !in deletedDates }.forEach { key ->
            result[key] = SyncRecord(stamp, value = scalar(if (key.endsWith("/note") || key.endsWith("/training")) "" else JSONObject.NULL))
        }
        if (replaceAll) {
            // All optional fields must be explicitly reset, including cloud fields not visible locally.
            after.logs.forEach { log ->
                logFields.forEach { field ->
                    val key = "l/${log.date}/$field"
                    result[key] = SyncRecord(stamp, value = fresh[key] ?: scalar(if (field == "note" || field == "training") "" else JSONObject.NULL))
                }
            }
            // Reset all known award histories. New imported events at this same stamp survive;
            // older events remain suppressed even if they arrive later from another snapshot.
            val awardIds = state.records.keys.filter { it.startsWith("a/") }
                .map { it.substring(2).substringBefore('/') }.toSet() + after.achievements.keys
            awardIds.forEach { id -> result["a/$id/@deleted"] = SyncRecord(stamp, deleted = true) }
        }
        require(result.size <= MAX_RECORDS) { tr(R.string.data_too_many_sync_records) }
        return SyncState(result.toSortedMap(), stamp)
    }

    fun materialize(state: SyncState): AppData {
        val products = JSONArray(); val entries = JSONArray(); val achievements = JSONObject(); val settings = JSONObject()
        val logs = linkedMapOf<String, JSONObject>()
        val goals = JSONObject().put("macros", JSONObject().put("kcal", 0).put("protein", 0).put("fat", 0).put("carbs", 0))
            .put("weight", JSONObject.NULL)
        state.records.toSortedMap().forEach { (key, record) ->
            if (record.deleted) return@forEach
            when {
                key.startsWith("p/") -> products.put(JSONObject(record.value!!))
                key.startsWith("e/") -> entries.put(JSONObject(record.value!!))
                key.startsWith("l/") -> {
                    val date = key.substring(2, 12); val field = key.substring(13)
                    val tombstone = state.records["l/$date/@deleted"]
                    if (tombstone == null || record.stamp > tombstone.stamp) {
                        logs.getOrPut(date) { JSONObject().put("date", date) }.put(field, unScalar(record.value!!))
                    }
                }
                key.startsWith("g/") -> {
                    val field = key.substring(2)
                    if (field == "weight") goals.put(field, unScalar(record.value!!))
                    else goals.getJSONObject("macros").put(field, unScalar(record.value!!))
                }
                key.startsWith("a/") -> {
                    val id = key.substring(2).substringBefore('/')
                    val reset = state.records["a/$id/@deleted"]
                    if (reset == null || record.stamp >= reset.stamp) {
                        val date = unScalar(record.value!!) as String
                        if (!achievements.has(id) || date < achievements.getString(id)) achievements.put(id, date)
                    }
                }
                key.startsWith("s/") -> settings.put(key.substring(2), unScalar(record.value!!))
            }
        }
        val visibleLogs = JSONArray()
        logs.values.filter { log -> logFields.any { field ->
            if (field == "note" || field == "training") log.optString(field).isNotBlank() else !log.isNull(field)
        } }.forEach(visibleLogs::put)
        return DataJson.decode(JSONObject().put("version", 1).put("products", products).put("entries", entries)
            .put("logs", visibleLogs).put("goals", goals).put("achievements", achievements).put("settings", settings).toString())
    }

    fun encode(data: AppData, state: SyncState, canonical: Boolean = false): String {
        val ordered = if (canonical) data.copy(products = data.products.sortedBy { it.id },
            entries = data.entries.sortedBy { it.id }, logs = data.logs.sortedBy { it.date }) else data
        val root = JSONObject(DataJson.encode(ordered)).put("version", 2)
        val records = JSONArray()
        state.records.toSortedMap().forEach { (key, record) -> records.put(JSONObject().put("key", key)
            .put("stamp", stampJson(record.stamp)).put("deleted", record.deleted)
            .put("value", record.value ?: JSONObject.NULL)) }
        val clock = if (canonical) state.records.values.maxOfOrNull { it.stamp } ?: SyncStamp.ZERO else state.clock
        root.put("sync", JSONObject().put("clock", stampJson(clock)).put("records", records))
        return root.toString(2)
    }

    fun decode(text: String): SyncDocument {
        val data = DataJson.decode(text)
        val root = JSONObject(text)
        require(root.getInt("version") == 2) { tr(R.string.data_sync_version_required) }
        val metadata = root.getJSONObject("sync")
        val clock = readStamp(metadata.getJSONObject("clock"))
        val array = metadata.getJSONArray("records")
        require(array.length() <= MAX_RECORDS) { tr(R.string.data_too_many_sync_records) }
        val records = linkedMapOf<String, SyncRecord>()
        repeat(array.length()) { index ->
            val obj = array.getJSONObject(index)
            val key = obj.get("key") as? String ?: throw IllegalArgumentException(tr(R.string.data_sync_key_invalid))
            require(key !in records) { tr(R.string.data_sync_key_duplicate) }
            val deleted = obj.get("deleted") as? Boolean ?: throw IllegalArgumentException(tr(R.string.data_deletion_flag_invalid))
            val value = if (obj.isNull("value")) null else obj.get("value") as? String
                ?: throw IllegalArgumentException(tr(R.string.data_sync_data_invalid))
            val record = SyncRecord(readStamp(obj.getJSONObject("stamp")), deleted, value)
            require(record.stamp <= clock) { tr(R.string.data_record_clock_invalid) }
            validateRecord(key, record)
            records[key] = record
        }
        val state = SyncState(records.toSortedMap(), clock)
        require(equivalent(data, materialize(state))) { tr(R.string.data_sync_metadata_mismatch) }
        return SyncDocument(data, state)
    }

    fun equivalent(a: AppData, b: AppData): Boolean =
        a.copy(products = a.products.sortedBy { it.id }, entries = a.entries.sortedBy { it.id }, logs = a.logs.sortedBy { it.date }) ==
            b.copy(products = b.products.sortedBy { it.id }, entries = b.entries.sortedBy { it.id }, logs = b.logs.sortedBy { it.date })

    private fun flatten(data: AppData): Map<String, String> {
        val root = JSONObject(DataJson.encode(data)); val values = linkedMapOf<String, String>()
        listOf("products" to "p/", "entries" to "e/").forEach { (name, prefix) ->
            val array = root.getJSONArray(name)
            repeat(array.length()) { i -> val item = array.getJSONObject(i); values[prefix + item.getString("id")] = item.toString() }
        }
        val logs = root.getJSONArray("logs")
        repeat(logs.length()) { i ->
            val log = logs.getJSONObject(i); val date = log.getString("date")
            logFields.forEach { field ->
                val value = log.get(field)
                if (value != JSONObject.NULL && value != "") values["l/$date/$field"] = scalar(value)
            }
        }
        val goals = root.getJSONObject("goals")
        goalFields.forEach { field -> values["g/$field"] = scalar(if (field == "weight") goals.get(field) else goals.getJSONObject("macros").get(field)) }
        data.achievements.forEach { (id, date) -> values["a/$id/$date"] = scalar(date) }
        data.settings.forEach { (key, value) -> values["s/$key"] = scalar(value) }
        return values
    }

    private fun validateRecord(key: String, record: SyncRecord) {
        require(key.length in 3..140) { tr(R.string.data_sync_key_length_invalid) }
        val id = key.substring(2)
        require(id.isNotBlank() && id.none { it.isISOControl() && it != '\n' && it != '\t' }) { tr(R.string.data_sync_key_malformed) }
        val kind = key.take(2)
        require(kind in setOf("p/", "e/", "l/", "g/", "a/", "s/")) { tr(R.string.data_sync_type_unknown) }
        if (kind == "p/" || kind == "e/") require(id.length <= 120) { tr(R.string.data_record_id_too_long) }
        if (kind == "a/") {
            val awardId = id.substringBefore('/'); val date = id.substringAfter('/', "")
            require(awardId.matches(Regex("[a-zA-Z0-9_]{1,100}"))) { tr(R.string.data_achievement_invalid) }
            if (date == "@deleted") require(record.deleted) { tr(R.string.data_achievement_deletion_invalid) }
            else DataJson.validDate(date)
        }
        if (kind == "l/") {
            require(key.length > 13 && key[12] == '/') { tr(R.string.data_log_key_invalid) }
            DataJson.validDate(key.substring(2, 12))
            val field = key.substring(13)
            require(field in logFields || field == "@deleted") { tr(R.string.data_log_field_unknown) }
            if (field == "@deleted") require(record.deleted) { tr(R.string.data_log_deletion_invalid) }
        }
        if (kind == "g/") require(id in goalFields && !record.deleted) { tr(R.string.data_goal_unknown) }
        if (kind == "s/") require(id in portableSettings) { tr(R.string.data_setting_not_portable) }
        if (record.deleted) {
            require(record.value == null) { tr(R.string.data_deleted_record_has_data) }
            return
        }
        require(record.value != null && record.value.length <= 20_000) { tr(R.string.data_sync_value_invalid) }
        val value = JSONObject(record.value)
        when (kind) {
            "p/", "e/" -> require(value.getString("id") == id) { tr(R.string.data_record_id_mismatch) }
            else -> require(value.has("v") && value.length() == 1) { tr(R.string.data_field_value_invalid) }
        }
        if (kind == "a/") require(unScalar(record.value) == id.substringAfter('/')) { tr(R.string.data_achievement_date_mismatch) }
        // Validate hidden fields too: a tombstone must not hide a malformed payload in a backup.
        val validationRecords = when (kind) {
            "p/", "e/" -> mapOf(key to record)
            "l/" -> {
                val v = unScalar(record.value)
                val field = key.substring(13)
                if (field == "note" || field == "training") {
                    require(v is String) { tr(R.string.data_note_string_required) }
                    require(v.length <= 2_000 && v.none { it.isISOControl() && it != '\n' && it != '\t' }) {
                        tr(R.string.data_sync_note_invalid)
                    }
                    if (v.isEmpty()) return
                } else if (v == JSONObject.NULL) return
                mapOf(key to record)
            }
            else -> mapOf(key to record)
        }
        materialize(SyncState(validationRecords, record.stamp))
    }

    private fun stampJson(stamp: SyncStamp) = JSONObject().put("millis", stamp.millis).put("counter", stamp.counter).put("device", stamp.device)
    private fun readStamp(obj: JSONObject): SyncStamp {
        fun exactLong(key: String): Long {
            val value = obj.get(key)
            require(value is Int || value is Long) { tr(R.string.data_logical_clock_invalid) }
            return (value as Number).toLong()
        }
        val millis = exactLong("millis"); val counter = exactLong("counter")
        val device = obj.get("device") as? String ?: throw IllegalArgumentException(tr(R.string.data_device_invalid))
        require(millis in 0..MAX_MILLIS && counter in 0 until Long.MAX_VALUE &&
            device.length in 1..120 && device.none { it.isISOControl() }) { tr(R.string.data_version_stamp_invalid) }
        return SyncStamp(millis, counter, device)
    }

    private fun scalar(value: Any): String = JSONObject().put("v", value).toString()
    private fun unScalar(value: String): Any = JSONObject(value).get("v")
    private fun isDefaultGoal(key: String, value: String): Boolean = key.startsWith("g/") &&
        (unScalar(value) == JSONObject.NULL || (unScalar(value) as? Number)?.toDouble() == 0.0)
}
