package ru.dietdiary.offline

import android.content.Context
import android.util.AtomicFile
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.time.LocalDate

data class Macros(
    val kcal: Double = 0.0,
    val protein: Double = 0.0,
    val fat: Double = 0.0,
    val carbs: Double = 0.0,
) {
    operator fun plus(other: Macros) = Macros(
        kcal + other.kcal, protein + other.protein, fat + other.fat, carbs + other.carbs,
    )

    /** Product values are always for 100 g; entry totals use the entered edible weight. */
    fun scaled(grams: Double) = Macros(
        kcal * grams / 100.0,
        protein * grams / 100.0,
        fat * grams / 100.0,
        carbs * grams / 100.0,
    )
}

data class Product(
    val id: String,
    val name: String,
    val category: String,
    val macros: Macros,
    val note: String = "",
    val favorite: Boolean = false,
)

data class Entry(
    val id: String,
    val date: String,
    val meal: String,
    val productId: String,
    val name: String,
    val grams: Double,
    val per100: Macros,
) {
    val total: Macros get() = per100.scaled(grams)
}

data class DailyLog(
    val date: String,
    val weight: Double? = null,
    val waist: Double? = null,
    val steps: Int? = null,
    val sleep: Double? = null,
    val note: String = "",
    val bodyFat: Double? = null,
    val training: String = "",
    val calories: Double? = null,
    val protein: Double? = null,
)

data class Goals(val macros: Macros = Macros(), val weight: Double? = null)

data class AppData(
    val products: List<Product>,
    val entries: List<Entry> = emptyList(),
    val logs: List<DailyLog> = emptyList(),
    val goals: Goals = Goals(),
)

/**
 * Owns the complete offline journal. A successful atomic disk write always precedes a state
 * change. Food records store their own names and nutrition snapshots, so catalog edits cannot
 * retroactively change a day's totals.
 *
 * A corrupt saved journal is never automatically replaced. In this case the catalog is still
 * browsable, but mutations are disabled until the user imports a valid backup.
 */
class AppStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, FILE_NAME))
    private var error by mutableStateOf<String?>(null)
    val loadError: String? get() = error
    val isReadOnly: Boolean get() = error != null

    var data by mutableStateOf(AppData(emptyList()))
        private set

    init {
        val savedExists = file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()
        if (savedExists) {
            try {
                data = DataJson.decode(readLimited(file))
            } catch (problem: Exception) {
                error = "Не удалось прочитать дневник. Исходный файл сохранён. " +
                    "Восстановите данные из резервной копии. ${problem.message.orEmpty()}"
                data = runCatching { loadCatalog(context) }.getOrElse { AppData(emptyList()) }
            }
        } else {
            try {
                data = loadCatalog(context)
            } catch (problem: Exception) {
                error = "Не удалось прочитать базу продуктов. ${problem.message.orEmpty()}"
            }
        }
    }

    @Synchronized
    fun saveProduct(product: Product) {
        commit(data.copy(products = data.products.replaceOrAppend({ it.id == product.id }, product)))
    }

    @Synchronized
    fun deleteProduct(id: String) {
        commit(data.copy(products = data.products.filterNot { it.id == id }))
    }

    @Synchronized
    fun addEntry(entry: Entry) {
        require(data.entries.none { it.id == entry.id }) { "Такая запись еды уже существует" }
        commit(data.copy(entries = data.entries + entry))
    }

    @Synchronized
    fun updateEntry(entry: Entry) {
        require(data.entries.any { it.id == entry.id }) { "Запись еды не найдена" }
        commit(data.copy(entries = data.entries.map { if (it.id == entry.id) entry else it }))
    }

    @Synchronized
    fun deleteEntry(id: String) {
        commit(data.copy(entries = data.entries.filterNot { it.id == id }))
    }

    @Synchronized
    fun saveLog(log: DailyLog) {
        commit(data.copy(logs = data.logs.replaceOrAppend({ it.date == log.date }, log).sortedBy { it.date }))
    }

    @Synchronized
    fun deleteLog(date: String) {
        commit(data.copy(logs = data.logs.filterNot { it.date == date }))
    }

    @Synchronized
    fun saveGoals(goals: Goals) {
        commit(data.copy(goals = goals))
    }

    @Synchronized
    fun exportJson(): String {
        checkWritable()
        return DataJson.encode(data)
    }

    /** Parses and validates the whole file before writing; a failed import cannot change data. */
    @Synchronized
    fun importJson(json: String) {
        val candidate = DataJson.decode(json)
        write(candidate)
        data = candidate
        error = null
    }

    private fun commit(candidate: AppData) {
        checkWritable()
        DataJson.validate(candidate)
        write(candidate)
        data = candidate
    }

    private fun checkWritable() {
        if (isReadOnly) throw IOException("Дневник защищён от перезаписи: восстановите резервную копию")
    }

    private fun write(candidate: AppData) {
        val bytes = DataJson.encode(candidate).toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BACKUP_BYTES) { "Резервная копия слишком большая" }
        var stream: FileOutputStream? = null
        try {
            stream = file.startWrite()
            stream.write(bytes)
            file.finishWrite(stream)
            stream = null
            // AtomicFile can report a failed rename only to Logcat on some Android releases.
            // Verify the committed bytes before publishing them to the UI.
            if (!file.readFully().contentEquals(bytes)) {
                throw IOException("Не удалось проверить сохранение дневника")
            }
        } catch (problem: Exception) {
            if (stream != null) file.failWrite(stream)
            if (problem is IOException) throw problem
            throw IOException("Не удалось сохранить дневник", problem)
        }
    }

    companion object {
        const val FILE_NAME = "balance_data.json"
        const val MAX_BACKUP_BYTES = 50 * 1024 * 1024

        /** Bounded reader for Storage Access Framework streams, compatible with Android 8. */
        fun readBackup(input: InputStream): String {
            val bytes = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                require(bytes.size() + count <= MAX_BACKUP_BYTES) { "Резервная копия больше 50 МБ" }
                bytes.write(buffer, 0, count)
            }
            return bytes.toString(Charsets.UTF_8.name())
        }

        private fun readLimited(file: AtomicFile): String = file.openRead().use { input ->
            require(input.channel.size() <= MAX_BACKUP_BYTES) { "Файл дневника слишком большой" }
            readBackup(input)
        }

        private fun loadCatalog(context: Context): AppData {
            val text = context.assets.open("products.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
            return DataJson.decodeCatalog(text)
        }
    }
}

private fun <T> List<T>.replaceOrAppend(matches: (T) -> Boolean, replacement: T): List<T> =
    if (any(matches)) map { if (matches(it)) replacement else it } else this + replacement

/** Versioned exchange format shared by backups and on-device persistence. */
internal object DataJson {
    private const val MAX_PRODUCTS = 10_000
    private const val MAX_ENTRIES = 100_000
    private const val MAX_LOGS = 36_600
    private val datePattern = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")

    fun decodeCatalog(text: String): AppData = parseSafely {
        val array = JSONArray(text)
        require(array.length() in 1..MAX_PRODUCTS) { "Неверный размер базы продуктов" }
        val products = List(array.length()) { index ->
            val item = array.getJSONObject(index)
            Product(
                id = item.string("id"),
                name = item.string("name"),
                category = item.string("category"),
                macros = readMacros(item),
                note = item.optionalString("note"),
                favorite = item.optionalBoolean("favorite"),
            )
        }
        AppData(products).also(::validate)
    }

    fun decode(text: String): AppData = parseSafely {
        require(text.length <= AppStore.MAX_BACKUP_BYTES) { "Резервная копия слишком большая" }
        require(text.toByteArray(Charsets.UTF_8).size <= AppStore.MAX_BACKUP_BYTES) {
            "Резервная копия слишком большая"
        }
        val root = JSONObject(text)
        require(root.integer("version") == 1) { "Неподдерживаемая версия резервной копии" }
        val products = root.array("products", MAX_PRODUCTS).mapObjects { item ->
            Product(
                item.string("id"), item.string("name"), item.string("category"),
                readMacros(item.getJSONObject("macros")), item.optionalString("note"),
                item.optionalBoolean("favorite"),
            )
        }
        val entries = root.array("entries", MAX_ENTRIES).mapObjects { item ->
            Entry(
                item.string("id"), item.string("date"), item.string("meal"),
                item.string("productId"), item.string("name"), item.number("grams"),
                readMacros(item.getJSONObject("per100")),
            )
        }
        val logs = root.array("logs", MAX_LOGS).mapObjects { item ->
            DailyLog(
                date = item.string("date"), weight = item.nullableNumber("weight"),
                waist = item.nullableNumber("waist"), steps = item.nullableInteger("steps"),
                sleep = item.nullableNumber("sleep"), note = item.optionalString("note"),
                bodyFat = item.nullableNumber("bodyFat"), training = item.optionalString("training"),
                calories = item.nullableNumber("calories"), protein = item.nullableNumber("protein"),
            )
        }
        val goal = root.getJSONObject("goals")
        val goals = Goals(readMacros(goal.getJSONObject("macros")), goal.nullableNumber("weight"))
        AppData(products, entries, logs, goals).also(::validate)
    }

    fun encode(data: AppData): String = JSONObject().apply {
        put("version", 1)
        put("products", JSONArray().apply {
            data.products.forEach { product ->
                put(JSONObject().apply {
                    put("id", product.id)
                    put("name", product.name)
                    put("category", product.category)
                    put("macros", writeMacros(product.macros))
                    put("note", product.note)
                    put("favorite", product.favorite)
                })
            }
        })
        put("entries", JSONArray().apply {
            data.entries.forEach { entry ->
                put(JSONObject().apply {
                    put("id", entry.id)
                    put("date", entry.date)
                    put("meal", entry.meal)
                    put("productId", entry.productId)
                    put("name", entry.name)
                    put("grams", entry.grams)
                    put("per100", writeMacros(entry.per100))
                })
            }
        })
        put("logs", JSONArray().apply {
            data.logs.forEach { log ->
                put(JSONObject().apply {
                    put("date", log.date)
                    put("weight", log.weight ?: JSONObject.NULL)
                    put("waist", log.waist ?: JSONObject.NULL)
                    put("steps", log.steps ?: JSONObject.NULL)
                    put("sleep", log.sleep ?: JSONObject.NULL)
                    put("note", log.note)
                    put("bodyFat", log.bodyFat ?: JSONObject.NULL)
                    put("training", log.training)
                    put("calories", log.calories ?: JSONObject.NULL)
                    put("protein", log.protein ?: JSONObject.NULL)
                })
            }
        })
        put("goals", JSONObject().apply {
            put("macros", writeMacros(data.goals.macros))
            put("weight", data.goals.weight ?: JSONObject.NULL)
        })
    }.toString(2)

    fun validate(data: AppData) {
        require(data.products.size <= MAX_PRODUCTS) { "Слишком много продуктов" }
        require(data.entries.size <= MAX_ENTRIES) { "Слишком много записей еды" }
        require(data.logs.size <= MAX_LOGS) { "Слишком много записей дневника" }
        require(data.products.map { it.id }.distinct().size == data.products.size) { "Повторяющийся ID продукта" }
        require(data.entries.map { it.id }.distinct().size == data.entries.size) { "Повторяющийся ID записи еды" }
        require(data.logs.map { it.date }.distinct().size == data.logs.size) { "Повторяющаяся дата дневника" }
        data.products.forEach { product ->
            validText(product.id, "ID продукта", 120)
            validText(product.name, "Название продукта", 160)
            validText(product.category, "Категория", 80)
            validText(product.note, "Примечание", 2_000, allowEmpty = true)
            validMacros(product.macros)
        }
        data.entries.forEach { entry ->
            validText(entry.id, "ID записи", 120)
            validDate(entry.date)
            validText(entry.meal, "Приём пищи", 80)
            validText(entry.productId, "ID продукта", 120)
            validText(entry.name, "Название продукта", 160)
            require(entry.grams.isFinite() && entry.grams > 0.0 && entry.grams <= 100_000.0) {
                "Вес еды должен быть больше 0 и не больше 100000 г"
            }
            validMacros(entry.per100)
        }
        data.logs.forEach { log ->
            validDate(log.date)
            log.weight?.let { validNumber(it, 1.0, 500.0, "Вес") }
            log.waist?.let { validNumber(it, 20.0, 400.0, "Талия") }
            log.steps?.let { require(it in 0..200_000) { "Количество шагов должно быть от 0 до 200000" } }
            log.sleep?.let { validNumber(it, 0.0, 24.0, "Сон") }
            log.bodyFat?.let { validNumber(it, 0.0, 100.0, "Доля жира") }
            log.calories?.let { validNumber(it, 0.0, 100_000.0, "Калории за день") }
            log.protein?.let { validNumber(it, 0.0, 10_000.0, "Белок за день") }
            validText(log.training, "Тренировка", 2_000, allowEmpty = true)
            validText(log.note, "Заметка", 2_000, allowEmpty = true)
            require(log.weight != null || log.waist != null || log.steps != null || log.sleep != null ||
                log.bodyFat != null || log.calories != null || log.protein != null ||
                log.training.isNotBlank() || log.note.isNotBlank()) {
                "Укажите хотя бы один показатель или заметку"
            }
        }
        validMacros(data.goals.macros, isGoal = true)
        data.goals.weight?.let { validNumber(it, 1.0, 500.0, "Целевой вес") }
    }

    private fun validMacros(macros: Macros, isGoal: Boolean = false) {
        validNumber(macros.kcal, 0.0, if (isGoal) 10_000.0 else 1_000.0, "Калории")
        val max = if (isGoal) 1_000.0 else 100.0
        validNumber(macros.protein, 0.0, max, "Белки")
        validNumber(macros.fat, 0.0, max, "Жиры")
        validNumber(macros.carbs, 0.0, max, "Углеводы")
    }

    private fun validNumber(value: Double, min: Double, max: Double, label: String) {
        require(value.isFinite() && value in min..max) { "$label: допустимый диапазон $min–$max" }
    }

    private fun validText(value: String, label: String, max: Int, allowEmpty: Boolean = false) {
        require(value.length <= max && (allowEmpty || value.isNotBlank())) { "$label: неверная длина" }
        require(value.none { it.isISOControl() && it != '\n' && it != '\t' }) { "$label: недопустимый символ" }
    }

    private fun validDate(value: String) {
        require(datePattern.matches(value)) { "Дата должна иметь формат ГГГГ-ММ-ДД" }
        try {
            LocalDate.parse(value)
        } catch (_: Exception) {
            throw IllegalArgumentException("Несуществующая дата: $value")
        }
    }

    private fun readMacros(item: JSONObject) = Macros(
        item.number("kcal"), item.number("protein"), item.number("fat"), item.number("carbs"),
    )

    private fun writeMacros(macros: Macros) = JSONObject().apply {
        put("kcal", macros.kcal)
        put("protein", macros.protein)
        put("fat", macros.fat)
        put("carbs", macros.carbs)
    }

    private fun JSONObject.string(key: String): String {
        val value = get(key)
        require(value is String) { "Поле $key должно быть строкой" }
        return value
    }

    private fun JSONObject.optionalString(key: String): String = if (has(key)) string(key) else ""

    private fun JSONObject.optionalBoolean(key: String): Boolean {
        if (!has(key)) return false
        val value = get(key)
        require(value is Boolean) { "Поле $key должно быть логическим значением" }
        return value
    }

    private fun JSONObject.number(key: String): Double {
        val value = get(key)
        require(value is Number) { "Поле $key должно быть числом" }
        return value.toDouble().also { require(it.isFinite()) { "Поле $key должно быть конечным числом" } }
    }

    private fun JSONObject.integer(key: String): Int {
        val value = number(key)
        require(value >= Int.MIN_VALUE && value <= Int.MAX_VALUE && value == value.toInt().toDouble()) {
            "Поле $key должно быть целым числом"
        }
        return value.toInt()
    }

    private fun JSONObject.nullableNumber(key: String): Double? = if (isNull(key)) null else number(key)
    private fun JSONObject.nullableInteger(key: String): Int? = if (isNull(key)) null else integer(key)

    private fun JSONObject.array(key: String, limit: Int): JSONArray = getJSONArray(key).also {
        require(it.length() <= limit) { "Слишком много элементов в $key" }
    }

    private fun <T> JSONArray.mapObjects(transform: (JSONObject) -> T): List<T> =
        List(length()) { transform(getJSONObject(it)) }

    private inline fun <T> parseSafely(block: () -> T): T = try {
        block()
    } catch (problem: IllegalArgumentException) {
        throw problem
    } catch (problem: Exception) {
        throw IllegalArgumentException("Некорректный файл данных: ${problem.message.orEmpty()}", problem)
    }
}
