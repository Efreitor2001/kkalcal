package ru.dietdiary.offline

import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Fake transport only: no OAuth credentials, Google account or network access is used. */
class DriveSnapshotsTest {
    @Test fun paginationRequestsOnlyThisFormatInAppDataAndSafelyEncodesPageTokens() {
        val requests = mutableListOf<Request>()
        val pageToken = "page/2 + next=&"
        val drive = DriveSnapshots("test-access") { url, method, token, body, type, limit ->
            val request = Request(url, method, token, body, type, limit).also(requests::add)
            val pageValue = query(request.url)["pageToken"]
            CloudHttpResponse(when (pageValue) {
                null -> page(listOf(snapshot("a", "phone-a", "2026-10-01T10:00:00Z")), pageToken)
                pageToken -> page(listOf(snapshot("b", "phone-b", "2026-10-02T10:00:00Z")))
                else -> error("Unexpected page token")
            })
        }
        val files = drive.list()
        assertEquals(listOf("a", "b"), files.map { it.id })
        assertEquals(listOf("phone-a", "phone-b"), files.map { it.writer })
        assertEquals(2, requests.size)
        requests.forEach { request ->
            val uri = URI(request.url)
            assertEquals("https", uri.scheme)
            assertEquals("www.googleapis.com", uri.host)
            assertEquals("/drive/v3/files", uri.path)
            val query = query(request.url)
            assertEquals("appDataFolder", query["spaces"])
            assertEquals("1000", query["pageSize"])
            assertEquals("trashed = false and 'appDataFolder' in parents and appProperties has { key='format' and value='dietdiary-v2' }", query["q"])
            assertEquals("nextPageToken,files(id,createdTime,appProperties)", query["fields"])
            assertEquals("GET", request.method)
            assertEquals("test-access", request.token)
            assertNull(request.body)
            assertEquals(1024 * 1024, request.maxBytes)
            assertFalse(request.url.contains("test-access"))
        }
        assertNull(query(requests.first().url)["pageToken"])
        assertEquals(pageToken, query(requests.last().url)["pageToken"])
    }

    @Test fun repeatingPaginationTokenFailsInsteadOfLoopingForever() {
        var calls = 0
        val drive = DriveSnapshots("test") { _, _, _, _, _, _ ->
            calls++
            CloudHttpResponse(page(emptyList(), "repeat"))
        }
        expectFailure<IllegalStateException> { drive.list() }
        assertEquals(2, calls)
    }

    @Test fun snapshotLimitStopsOversizedListings() {
        val drive = DriveSnapshots("test") { _, _, _, _, _, _ ->
            CloudHttpResponse(page((0..10_000).map { snapshot("id_$it", "writer", "2026-10-01T10:00:00Z") }))
        }
        expectFailure<IllegalArgumentException> { drive.list() }
    }

    @Test fun twoIndependentUploadsCreateSeparateFilesWithoutReplacingEachOther() {
        val cloud = FakeDrive()
        val phoneA = DriveSnapshots("account-token", cloud::request)
        val phoneB = DriveSnapshots("account-token", cloud::request)
        // Both installations start from the same empty remote listing.
        assertTrue(phoneA.list().isEmpty())
        assertTrue(phoneB.list().isEmpty())
        val jsonA = "{\"version\":2,\"note\":\"Еда телефона А\"}"
        val jsonB = "{\"version\":2,\"note\":\"Вес телефона Б\"}"
        val idA = phoneA.upload(jsonA, "installation-a")
        val idB = phoneB.upload(jsonB, "installation-b")
        assertNotEquals(idA, idB)
        val files = phoneA.list()
        assertEquals(setOf(idA, idB), files.map { it.id }.toSet())
        assertEquals(jsonA, phoneB.download(files.single { it.id == idA }))
        assertEquals(jsonB, phoneA.download(files.single { it.id == idB }))
        val uploads = cloud.requests.filter { it.method == "POST" }
        assertEquals(2, uploads.size)
        assertTrue(cloud.requests.none { it.method == "PATCH" || it.method == "PUT" || it.method == "DELETE" })
        assertEquals(2, cloud.files.values.map { it.name }.distinct().size)
        uploads.forEach { request ->
            assertEquals("/upload/drive/v3/files", URI(request.url).path)
            assertEquals(mapOf("uploadType" to "multipart", "fields" to "id"), query(request.url))
            assertEquals("account-token", request.token)
            assertEquals(1024 * 1024, request.maxBytes)
            assertTrue(request.contentType.startsWith("multipart/related; boundary=dietdiary-"))
        }
    }

    @Test fun multipartPreservesUtf8DocumentAndHidesItUnderAppDataFolder() {
        val cloud = FakeDrive()
        val drive = DriveSnapshots("test-access", cloud::request)
        val json = "{\n  \"note\": \"Съедено 125,5 г — ёжевика\",\n  \"tombstones\": [\"deleted-id\"]\n}"
        val id = drive.upload(json, "phone-one")
        val stored = cloud.files.getValue(id)
        assertEquals(json, stored.body)
        assertEquals("phone-one", stored.writer)
        assertEquals("application/json", stored.mimeType)
        assertEquals(listOf("appDataFolder"), stored.parents)
        assertEquals("dietdiary-v2", stored.format)
        assertTrue(stored.name.startsWith("diet-diary-") && stored.name.endsWith(".json"))
        assertFalse(stored.name.contains("phone-one"))
    }

    @Test fun pruningKeepsNewAndNewestPreviousSnapshotAndNeverDeletesAnotherWriter() {
        val cloud = FakeDrive()
        val drive = DriveSnapshots("test", cloud::request)
        val oldA = drive.upload("{\"revision\":1}", "writer-a")
        val midA = drive.upload("{\"revision\":2}", "writer-a")
        val previousA = drive.upload("{\"revision\":3}", "writer-a")
        val other = drive.upload("{\"revision\":4}", "writer-b")
        val noWriter = drive.upload("{\"revision\":5}", "")
        val beforeUpload = drive.list()
        val newA = drive.upload("{\"revision\":6}", "writer-a")
        drive.pruneOwnOlderCopies(beforeUpload, "writer-a")
        assertEquals(setOf(previousA, newA, other, noWriter), cloud.files.keys)
        val deleted = cloud.requests.filter { it.method == "DELETE" }.map { URI(it.url).path.substringAfterLast('/') }.toSet()
        assertEquals(setOf(oldA, midA), deleted)
        assertEquals(2, cloud.files.values.count { it.writer == "writer-a" })
    }

    @Test fun listDownloadAndUploadFailuresPropagateWithoutPretendingSuccess() {
        val error = CloudHttpException(503)
        val drive = DriveSnapshots("test") { _, _, _, _, _, _ -> throw error }
        assertSame(error, expectFailure<CloudHttpException> { drive.list() })
        assertSame(error, expectFailure<CloudHttpException> { drive.download(DriveSnapshot("id", "writer", "date")) })
        assertSame(error, expectFailure<CloudHttpException> { drive.upload("{}", "writer") })
    }

    @Test fun failureOnLaterPageDoesNotReturnAnIncompleteListing() {
        var calls = 0
        val drive = DriveSnapshots("test") { _, _, _, _, _, _ ->
            calls++
            if (calls == 1) CloudHttpResponse(page(listOf(snapshot("first", "writer", "date")), "next"))
            else throw IOException("offline")
        }
        expectFailure<IOException> { drive.list() }
        assertEquals(2, calls)
    }

    @Test fun optionalCleanupContinuesAfterDeleteFailure() {
        val deleted = mutableListOf<String>()
        val drive = DriveSnapshots("test") { url, method, _, _, _, _ ->
            assertEquals("DELETE", method)
            deleted += URI(url).path.substringAfterLast('/')
            throw CloudHttpException(404)
        }
        drive.pruneOwnOlderCopies(listOf(
            DriveSnapshot("oldest", "owner", "2026-10-01T10:00:00Z"),
            DriveSnapshot("older", "owner", "2026-10-02T10:00:00Z"),
            DriveSnapshot("previous", "owner", "2026-10-03T10:00:00Z"),
            DriveSnapshot("other", "someone-else", "2026-10-01T10:00:00Z")), "owner")
        assertEquals(setOf("oldest", "older"), deleted.toSet())
    }

    @Test fun downloadedIdCannotIntroduceExtraPathSegmentsOrQueryParameters() {
        var called: Request? = null
        val drive = DriveSnapshots("secret-header") { url, method, token, body, type, max ->
            called = Request(url, method, token, body, type, max)
            CloudHttpResponse("{}")
        }
        drive.download(DriveSnapshot("id/part?access_token=wrong&alt=other", "writer", "date"))
        val request = called!!
        assertTrue(URI(request.url).rawPath.contains("id%2Fpart%3Faccess_token%3Dwrong%26alt%3Dother"))
        assertEquals(mapOf("alt" to "media"), query(request.url))
        assertEquals("secret-header", request.token)
        assertEquals(AppStore.MAX_BACKUP_BYTES, request.maxBytes)
        assertFalse(request.url.contains("secret-header"))
    }

    @Test fun hashesUseStableSha256Utf8AndChangeWithContent() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", DriveSnapshots.digest(""))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", DriveSnapshots.digest("abc"))
        assertEquals(DriveSnapshots.digest("Запись"), DriveSnapshots.digest("Запись"))
        assertNotEquals(DriveSnapshots.digest("Запись"), DriveSnapshots.digest("Запись "))
    }

    @Test fun largeUtf8SnapshotUsesResumableMetadataThenUploadsTheCompleteDocument() {
        val json = "{\"note\":\"${"я".repeat(2_700_000)}\"}"
        assertTrue(json.length < 5 * 1024 * 1024)
        assertTrue(json.toByteArray(Charsets.UTF_8).size > 5 * 1024 * 1024)
        val calls = mutableListOf<Request>()
        val location = "https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&upload_id=session-one"
        val drive = DriveSnapshots("private-token") { url, method, token, body, type, max ->
            calls += Request(url, method, token, body, type, max)
            when (method) {
                "POST" -> CloudHttpResponse("", location)
                "PUT" -> CloudHttpResponse("{\"id\":\"large-snapshot\"}")
                else -> error("Unexpected method")
            }
        }
        assertEquals("large-snapshot", drive.upload(json, "large-writer"))
        assertEquals(2, calls.size)
        val initiate = calls[0]
        assertEquals(mapOf("uploadType" to "resumable", "fields" to "id"), query(initiate.url))
        val metadata = JSONObject(initiate.body!!.toString(Charsets.UTF_8))
        assertEquals("appDataFolder", metadata.getJSONArray("parents").getString(0))
        assertEquals("dietdiary-v2", metadata.getJSONObject("appProperties").getString("format"))
        assertEquals("large-writer", metadata.getJSONObject("appProperties").getString("writer"))
        assertEquals("application/json", metadata.getString("mimeType"))
        assertFalse(metadata.has("note"))
        val upload = calls[1]
        assertEquals(location, upload.url)
        assertEquals("PUT", upload.method)
        assertEquals(json, upload.body!!.toString(Charsets.UTF_8))
        assertEquals("application/json; charset=UTF-8", upload.contentType)
        calls.forEach {
            assertEquals("private-token", it.token)
            assertFalse(it.url.contains("private-token"))
            assertEquals(1024 * 1024, it.maxBytes)
        }
    }

    @Test fun resumableUploadRejectsMissingOrUntrustedLocationsBeforeSendingTheDiary() {
        val json = "{\"data\":\"${"x".repeat(5 * 1024 * 1024)}\"}"
        val locations = listOf(null,
            "http://www.googleapis.com/upload/drive/v3/files?upload_id=a",
            "https://www.googleapis.com.evil.example/upload/drive/v3/files?upload_id=a",
            "https://user@www.googleapis.com/upload/drive/v3/files?upload_id=a",
            "https://www.googleapis.com:444/upload/drive/v3/files?upload_id=a",
            "https://oauth2.googleapis.com/upload/drive/v3/files?upload_id=a",
            "https://www.googleapis.com/drive/v3/files?upload_id=a",
            "https://www.googleapis.com/upload/drive/v3/files/../files?upload_id=a",
            "https://www.googleapis.com/upload/drive/v3/files/%2E%2E?upload_id=a",
            "https://www.googleapis.com/upload/drive/v3/files?upload_id=a#fragment")
        locations.forEach { badLocation ->
            var calls = 0
            val drive = DriveSnapshots("private-token") { _, method, _, _, _, _ ->
                calls++
                assertEquals("POST", method)
                CloudHttpResponse("", badLocation)
            }
            expectFailure<IllegalArgumentException> { drive.upload(json, "writer") }
            assertEquals(1, calls)
        }
    }

    @Test fun failedLargeUploadPropagatesAndRetryCreatesANewIndependentSession() {
        val json = "{\"data\":\"${"x".repeat(5 * 1024 * 1024)}\"}"
        val names = mutableListOf<String>()
        val destinations = mutableListOf<String>()
        var session = 0
        val error = CloudHttpException(503)
        val drive = DriveSnapshots("test-token") { url, method, _, body, _, _ ->
            if (method == "POST") {
                session++
                names += JSONObject(body!!.toString(Charsets.UTF_8)).getString("name")
                CloudHttpResponse("", "https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&upload_id=$session")
            } else {
                assertEquals("PUT", method)
                destinations += url
                if (session == 1) throw error
                CloudHttpResponse("{\"id\":\"retry-snapshot\"}")
            }
        }
        assertSame(error, expectFailure<CloudHttpException> { drive.upload(json, "writer") })
        assertEquals("retry-snapshot", drive.upload(json, "writer"))
        assertEquals(2, session)
        assertNotEquals(names[0], names[1])
        assertNotEquals(destinations[0], destinations[1])
    }

    @Test fun failedResumableInitiationNeverSendsAContentPut() {
        val json = "{\"data\":\"${"x".repeat(5 * 1024 * 1024)}\"}"
        val failure = CloudHttpException(429)
        var calls = 0
        val drive = DriveSnapshots("test") { _, method, _, _, _, _ ->
            calls++
            assertEquals("POST", method)
            throw failure
        }
        assertSame(failure, expectFailure<CloudHttpException> { drive.upload(json, "writer") })
        assertEquals(1, calls)
    }

    private data class Request(val url: String, val method: String, val token: String?, val body: ByteArray?,
        val contentType: String, val maxBytes: Int)

    private data class StoredFile(val id: String, val name: String, val body: String, val writer: String,
        val format: String, val mimeType: String, val parents: List<String>, val created: String)

    /** Models immutable create/list/download/delete semantics; it never talks to an HTTP server. */
    private class FakeDrive {
        val requests = mutableListOf<Request>()
        val files = linkedMapOf<String, StoredFile>()
        private var sequence = 0

        fun request(url: String, method: String, token: String?, body: ByteArray?, type: String, max: Int): CloudHttpResponse =
            CloudHttpResponse(handle(url, method, token, body, type, max))

        private fun handle(url: String, method: String, token: String?, body: ByteArray?, type: String, max: Int): String {
            requests += Request(url, method, token, body, type, max)
            val uri = URI(url)
            require(uri.host == "www.googleapis.com" && uri.scheme == "https")
            if (method == "POST") {
                assertEquals("/upload/drive/v3/files", uri.path)
                val boundary = type.substringAfter("boundary=")
                val parts = body!!.toString(Charsets.UTF_8).split("--$boundary")
                assertEquals(4, parts.size)
                assertEquals("--\r\n", parts.last())
                assertTrue(parts[1].startsWith("\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n"))
                assertTrue(parts[2].startsWith("\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n"))
                val metadata = JSONObject(parts[1].substringAfter("\r\n\r\n").removeSuffix("\r\n"))
                val content = parts[2].substringAfter("\r\n\r\n").removeSuffix("\r\n")
                val properties = metadata.getJSONObject("appProperties")
                val parents = metadata.getJSONArray("parents")
                sequence++
                val id = "remote-$sequence"
                files[id] = StoredFile(id, metadata.getString("name"), content, properties.getString("writer"),
                    properties.getString("format"), metadata.getString("mimeType"),
                    List(parents.length()) { parents.getString(it) }, "2026-10-09T10:00:${sequence.toString().padStart(2, '0')}Z")
                return JSONObject().put("id", id).toString()
            }
            if (uri.path == "/drive/v3/files") {
                assertEquals("GET", method)
                assertEquals("appDataFolder", query(url)["spaces"])
                return page(files.values.filter { it.format == "dietdiary-v2" && "appDataFolder" in it.parents }
                    .map { snapshot(it.id, it.writer, it.created) })
            }
            val id = URLDecoder.decode(uri.rawPath.substringAfterLast('/'), Charsets.UTF_8.name())
            if (method == "DELETE") {
                if (files.remove(id) == null) throw CloudHttpException(404)
                return ""
            }
            assertEquals("GET", method)
            assertEquals("media", query(url)["alt"])
            return files[id]?.body ?: throw CloudHttpException(404)
        }
    }

    companion object {
        private fun query(url: String): Map<String, String> = URI(url).rawQuery.orEmpty().split('&')
            .filter { it.isNotBlank() }.associate { part ->
                val fields = part.split('=', limit = 2)
                URLDecoder.decode(fields[0], Charsets.UTF_8.name()) to
                    URLDecoder.decode(fields.getOrElse(1) { "" }, Charsets.UTF_8.name())
            }

        private fun snapshot(id: String, writer: String, created: String): JSONObject = JSONObject()
            .put("id", id).put("createdTime", created)
            .put("appProperties", JSONObject().put("writer", writer).put("format", "dietdiary-v2"))

        private fun page(files: List<JSONObject>, next: String? = null): String = JSONObject()
            .put("files", JSONArray(files)).also { if (next != null) it.put("nextPageToken", next) }.toString()

        private inline fun <reified T : Throwable> expectFailure(block: () -> Unit): T {
            try { block() } catch (error: Throwable) {
                if (error is T) return error
                throw AssertionError("Expected ${T::class.java.simpleName}, received ${error.javaClass.simpleName}", error)
            }
            fail("Expected ${T::class.java.simpleName}")
            error("unreachable")
        }
    }
}
