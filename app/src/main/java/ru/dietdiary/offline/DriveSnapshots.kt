package ru.dietdiary.offline

import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.UUID

internal data class DriveSnapshot(val id: String, val writer: String, val created: String)

/**
 * Every upload creates an immutable snapshot. Devices never overwrite a shared Drive file.
 * Each snapshot carries complete merge metadata, including tombstones. Only the uploading
 * installation's older snapshots are pruned, after a new snapshot is committed by Drive.
 */
internal class DriveSnapshots(
    private val token: String,
    private val transport: (url: String, method: String, token: String?, body: ByteArray?, contentType: String, maxBytes: Int) -> CloudHttpResponse = CloudHttp::exchange,
) {
    fun list(): List<DriveSnapshot> {
        val snapshots = mutableListOf<DriveSnapshot>()
        var page: String? = null
        val seenPages = mutableSetOf<String>()
        do {
            val query = "trashed = false and 'appDataFolder' in parents and appProperties has { key='format' and value='dietdiary-v2' }"
            val url = "https://www.googleapis.com/drive/v3/files?spaces=appDataFolder&pageSize=1000&q=${escape(query)}" +
                "&fields=${escape("nextPageToken,files(id,createdTime,appProperties)")}" +
                (page?.let { "&pageToken=${escape(it)}" } ?: "")
            val result = JSONObject(request(url, maxBytes = 1024 * 1024))
            val files = result.getJSONArray("files")
            for (index in 0 until files.length()) {
                val file = files.getJSONObject(index)
                snapshots += DriveSnapshot(file.getString("id"),
                    file.optJSONObject("appProperties")?.optString("writer").orEmpty(), file.optString("createdTime"))
            }
            require(snapshots.size <= 10_000) { "Слишком много облачных копий" }
            page = result.optString("nextPageToken").takeIf { it.isNotEmpty() }
            if (page != null) check(seenPages.add(page)) { "Повтор страницы Google Drive" }
        } while (page != null)
        return snapshots
    }

    fun download(snapshot: DriveSnapshot): String = request(
        "https://www.googleapis.com/drive/v3/files/${escape(snapshot.id)}?alt=media")

    fun upload(json: String, writer: String): String {
        val payload = json.toByteArray(Charsets.UTF_8)
        require(payload.size <= AppStore.MAX_BACKUP_BYTES) { "Облачная копия больше 50 МБ" }
        val metadata = JSONObject().put("name", "diet-diary-${UUID.randomUUID()}.json")
            .put("parents", org.json.JSONArray().put("appDataFolder"))
            .put("mimeType", "application/json")
            .put("appProperties", JSONObject().put("format", "dietdiary-v2").put("writer", writer))
        if (payload.size > MULTIPART_LIMIT) {
            // Drive recommends resumable upload for documents over 5 MiB. Retrying a failed
            // exchange creates a new immutable snapshot rather than overwriting another device.
            val initiated = transport("https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&fields=id",
                "POST", token, metadata.toString().toByteArray(Charsets.UTF_8), "application/json; charset=UTF-8", 1024 * 1024)
            val destination = validatedUploadLocation(initiated.location)
            return JSONObject(request(destination, "PUT", payload, "application/json; charset=UTF-8", 1024 * 1024)).getString("id")
        }
        val boundary = "dietdiary-${UUID.randomUUID()}"
        val body = ("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$metadata\r\n" +
            "--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$json\r\n--$boundary--\r\n").toByteArray(Charsets.UTF_8)
        val result = JSONObject(request("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id",
            "POST", body, "multipart/related; boundary=$boundary", 1024 * 1024))
        return result.getString("id")
    }

    fun pruneOwnOlderCopies(previous: List<DriveSnapshot>, writer: String) {
        // Keep the new upload and the newest previous snapshot. Cleanup is optional.
        previous.filter { it.writer == writer }.sortedByDescending { it.created }.drop(1).forEach {
            runCatching { request("https://www.googleapis.com/drive/v3/files/${escape(it.id)}", "DELETE") }
        }
    }

    private fun request(url: String, method: String = "GET", body: ByteArray? = null,
        contentType: String = "application/json; charset=UTF-8", maxBytes: Int = AppStore.MAX_BACKUP_BYTES): String =
        transport(url, method, token, body, contentType, maxBytes).body

    private fun validatedUploadLocation(value: String?): String {
        require(value != null && value == value.trim()) { "Google Drive не вернул адрес загрузки" }
        val address = URI(value)
        val allowedPath = address.rawPath == "/upload/drive/v3/files" ||
            address.rawPath?.matches(Regex("/upload/drive/v3/files/[A-Za-z0-9_-]+")) == true
        require(address.scheme == "https" && address.host == "www.googleapis.com" &&
            address.rawUserInfo == null && address.port in setOf(-1, 443) && address.rawFragment == null && allowedPath) {
            "Недопустимый адрес загрузки Google Drive"
        }
        return value
    }

    companion object {
        private const val MULTIPART_LIMIT = 5 * 1024 * 1024
        private fun escape(value: String) = URLEncoder.encode(value, Charsets.UTF_8.name())
        fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
