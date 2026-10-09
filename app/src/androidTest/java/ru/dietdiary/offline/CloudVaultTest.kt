package ru.dietdiary.offline

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class CloudVaultTest {
    private fun isolated(block: (Context, File) -> Unit) {
        val application = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(application.cacheDir, "vault-test-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val context = object : ContextWrapper(application) { override fun getNoBackupFilesDir(): File = directory }
        try { block(context, directory) } finally { directory.deleteRecursively() }
    }

    @Test fun encryptedSessionSurvivesNewInstanceAndClearRemovesIt() = isolated { context, directory ->
        val secret = "synthetic-refresh-token-do-not-log"
        CloudVault(context).write(JSONObject().put("refresh", secret).put("sub", "test-account"))
        val file = File(directory, "google-session.enc")
        assertFalse(file.readText(Charsets.ISO_8859_1).contains(secret))
        assertEquals(secret, CloudVault(context).read()!!.getString("refresh"))
        CloudVault(context).clear()
        assertNull(CloudVault(context).read())
        assertFalse(file.exists())
    }

    @Test fun changedCiphertextIsRejectedAndNeverReplacedWithAnEmptySession() = isolated { context, directory ->
        CloudVault(context).write(JSONObject().put("refresh", "synthetic"))
        val file = File(directory, "google-session.enc")
        val bytes = file.readBytes()
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        file.writeBytes(bytes)
        assertTrue(runCatching { CloudVault(context).read() }.isFailure)
        assertArrayEquals(bytes, file.readBytes())
    }
}
