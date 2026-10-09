package ru.dietdiary.offline

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URL
import javax.net.ssl.HttpsURLConnection

internal class CloudHttpException(val status: Int) : IOException("Cloud HTTP $status")
internal class GoogleLoginRequired : IOException("Необходим повторный вход в Google")
internal data class CloudHttpResponse(val body: String, val location: String? = null)

/** No request/response bodies, URLs with credentials, or diary values are logged. */
internal object CloudHttp {
    private val hosts = setOf("www.googleapis.com", "oauth2.googleapis.com", "openidconnect.googleapis.com")

    fun request(url: String, method: String = "GET", token: String? = null,
                body: ByteArray? = null, contentType: String = "application/json; charset=UTF-8",
                maxBytes: Int = AppStore.MAX_BACKUP_BYTES): String =
        exchange(url, method, token, body, contentType, maxBytes).body

    fun exchange(url: String, method: String = "GET", token: String? = null,
                 body: ByteArray? = null, contentType: String = "application/json; charset=UTF-8",
                 maxBytes: Int = AppStore.MAX_BACKUP_BYTES): CloudHttpResponse {
        val address = URL(url)
        require(address.protocol == "https" && address.host in hosts && address.userInfo == null)
        val connection = address.openConnection() as HttpsURLConnection
        try {
            connection.requestMethod = method
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("Accept", "application/json")
            token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", contentType)
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            val status = connection.responseCode
            if (status !in 200..299) throw CloudHttpException(status)
            val location = connection.getHeaderField("Location")
            if (status == 204) return CloudHttpResponse("", location)
            val result = connection.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (out.size().toLong() + count > maxBytes) throw IOException("Cloud response too large")
                    out.write(buffer, 0, count)
                }
                out.toString(Charsets.UTF_8.name())
            }
            return CloudHttpResponse(result, location)
        } finally { connection.disconnect() }
    }

    fun objectRequest(url: String, token: String): JSONObject = JSONObject(request(url, token = token, maxBytes = 1024 * 1024))
}
