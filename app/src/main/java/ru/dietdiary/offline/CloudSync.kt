package ru.dietdiary.offline

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.work.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.openid.appauth.*
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class CloudStatus(val email: String? = null, val automatic: Boolean = true,
    val busy: Boolean = false, val message: String = "Google не подключён", val lastSuccess: Long = 0L)

class CloudSync private constructor(private val context: Context) {
    private val preferences = context.getSharedPreferences("cloud-device", Context.MODE_PRIVATE)
    private val vault = CloudVault(context)
    private val lock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val service by lazy { AuthorizationService(context) }
    private var session: JSONObject? = null
    private var started = false
    var status by mutableStateOf(CloudStatus(automatic = preferences.getBoolean("automatic", true)))
        private set
    val configured: Boolean get() = BuildConfig.GOOGLE_CLIENT_ID.matches(Regex("[0-9]+-[A-Za-z0-9_-]+\\.apps\\.googleusercontent\\.com"))

    init {
        try {
            session = vault.read()
            status = status.copy(email = session?.optString("email")?.takeIf { it.isNotBlank() },
                lastSuccess = if (preferences.getString("cacheAccount", null) == session?.optString("sub")) preferences.getLong("lastSuccess", 0) else 0,
                message = if (session != null) "Готово к синхронизации" else "Google не подключён")
        } catch (_: Exception) {
            status = status.copy(message = "Необходим повторный вход в Google")
        }
    }

    @OptIn(FlowPreview::class)
    @Synchronized fun start() {
        if (started) return
        started = true
        scope.launch {
            AppStore.get(context).localRevision.drop(1).debounce(5_000).collect {
                if (status.email != null && status.automatic) {
                    status = status.copy(message = if(hasNetwork()) "Изменения ожидают синхронизации…" else "Нет подключения к интернету. Изменения сохранены на устройстве.")
                    scheduleSoon()
                }
            }
        }
        reschedulePeriodic()
    }

    fun onAppStart() {
        start()
        if (status.email != null && status.automatic) scheduleSoon()
    }

    fun setAutomatic(enabled: Boolean) {
        preferences.edit().putBoolean("automatic", enabled).apply()
        status = status.copy(automatic = enabled)
        reschedulePeriodic()
        if (enabled && status.email != null) scheduleSoon()
        if (!enabled) WorkManager.getInstance(context).cancelUniqueWork(CHANGE_WORK)
    }

    fun authorizationIntent(): Intent {
        check(configured) { "В этой сборке подключение Google ещё не настроено" }
        check(!status.busy) { "Дождитесь завершения синхронизации" }
        val config = AuthorizationServiceConfiguration(Uri.parse("https://accounts.google.com/o/oauth2/v2/auth"),
            Uri.parse("https://oauth2.googleapis.com/token"))
        // AppAuth generates cryptographic state and S256 PKCE for every request.
        val request = AuthorizationRequest.Builder(config, BuildConfig.GOOGLE_CLIENT_ID,
            ResponseTypeValues.CODE, Uri.parse(REDIRECT_URI))
            .setScope("openid email $DRIVE_SCOPE")
            .setPrompt("consent select_account")
            .setAdditionalParameters(mapOf("access_type" to "offline"))
            .build()
        preferences.edit().putString("pendingState", request.state)
            .putLong("pendingAt", System.currentTimeMillis()).commit()
        return service.getAuthorizationRequestIntent(request)
    }

    suspend fun completeAuthorization(intent: Intent?) = withContext(Dispatchers.IO) {
        lock.withLock {
            status = status.copy(busy = true, message = "Подключение Google…")
            try {
                val expected = preferences.getString("pendingState", null)
                val age = System.currentTimeMillis() - preferences.getLong("pendingAt", 0)
                preferences.edit().remove("pendingState").remove("pendingAt").commit()
                if (intent == null) { status = status.copy(message = "Подключение отменено"); return@withLock }
                val response = AuthorizationResponse.fromIntent(intent)
                if (response == null) { status = status.copy(message = "Подключение отменено или отклонено Google"); return@withLock }
                require(expected != null && response.state == expected && age in 0..900_000)
                require(response.request.clientId == BuildConfig.GOOGLE_CLIENT_ID && response.request.redirectUri.toString() == REDIRECT_URI)
                require(response.request.configuration.tokenEndpoint.toString() == "https://oauth2.googleapis.com/token")
                require(response.request.codeVerifier != null && response.request.codeVerifierChallengeMethod == "S256")
                val tokens = suspendCancellableCoroutine<TokenResponse> { continuation ->
                    service.performTokenRequest(response.createTokenExchangeRequest()) { result, error ->
                        if (continuation.isActive) {
                            if (result != null) continuation.resume(result)
                            else continuation.resumeWithException(error ?: GoogleLoginRequired())
                        }
                    }
                }
                require((tokens.scope ?: response.scope ?: response.request.scope).orEmpty().split(' ').contains(DRIVE_SCOPE))
                val auth = AuthState(response, tokens, null)
                val access = tokens.accessToken ?: throw GoogleLoginRequired()
                val profile = CloudHttp.objectRequest("https://openidconnect.googleapis.com/v1/userinfo", access)
                val sub = profile.getString("sub")
                val email = profile.getString("email")
                require(sub.isNotBlank() && sub.length <= 255 && email.length <= 320 && profile.optBoolean("email_verified"))
                val next = JSONObject().put("auth", auth.jsonSerialize()).put("sub", sub).put("email", email)
                ensureActive()
                vault.write(next)
                val sameAccount = session?.optString("sub") == sub
                session = next
                if (!sameAccount) preferences.edit().remove("lastHash").remove("lastSuccess").remove("seenFiles").commit()
                status = status.copy(email = email, lastSuccess = if (sameAccount) status.lastSuccess else 0,
                    message = "Google подключён. Объединяем записи…")
                reschedulePeriodic()
                scheduleManual()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                status = status.copy(message = "Не удалось подключить Google. Проверьте интернет и настройку доступа.")
                Log.w(TAG, "oauth_failed")
            } finally { status = status.copy(busy = false) }
        }
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        // The same lock prevents late refresh or sync callbacks from recreating a signed-out session.
        lock.withLock {
            WorkManager.getInstance(context).cancelAllWorkByTag(WORK_TAG)
            vault.clear()
            session = null
            preferences.edit().remove("cacheAccount").remove("lastHash").remove("lastSuccess").remove("seenFiles")
                .remove("pendingState").remove("pendingAt").commit()
            status = CloudStatus(automatic = status.automatic, message = "Аккаунт отключён. Данные остались на устройстве.")
        }
    }

    fun syncNow() {
        if (status.email == null) return
        if (!hasNetwork()) {
            status = status.copy(message = "Нет подключения к интернету. Синхронизация ожидает сеть.")
        } else status = status.copy(message = "Синхронизация запланирована…")
        scheduleManual()
    }

    internal suspend fun synchronize(): Boolean = withContext(Dispatchers.IO) {
        lock.withLock {
            if (session == null) return@withLock true
            status = status.copy(busy = true, message = "Синхронизация…")
            try {
                if (!hasNetwork()) throw java.net.UnknownHostException()
                val store = AppStore.get(context)
                check(!store.isReadOnly)
                val account = session!!.getString("sub")
                val cacheMatches = preferences.getString("cacheAccount", null) == account
                val drive = DriveSnapshots(freshToken())
                val files = drive.list()
                val seen = (if(cacheMatches) preferences.getStringSet("seenFiles", emptySet()).orEmpty() else emptySet()).toMutableSet()
                for (remote in files) {
                    if (remote.id in seen) continue
                    val json = drive.download(remote)
                    ensureActive()
                    // Merge always uses the CURRENT store, including edits made during the network call.
                    store.mergeSyncJson(json)
                    seen += remote.id
                }
                ensureActive()
                val json = store.exportSyncJson()
                val hash = DriveSnapshots.digest(json)
                if (!cacheMatches || hash != preferences.getString("lastHash", null) || files.isEmpty()) {
                    val writer = preferences.getString("writer", null) ?: UUID.randomUUID().toString().also {
                        check(preferences.edit().putString("writer", it).commit())
                    }
                    seen += drive.upload(json, writer)
                    ensureActive()
                    drive.pruneOwnOlderCopies(files, writer)
                }
                ensureActive()
                // Prune only the local cache of IDs; forgotten IDs merely get downloaded again.
                val known = files.map { it.id }.toSet()
                val now = System.currentTimeMillis()
                check(preferences.edit().putString("cacheAccount", account).putString("lastHash", hash)
                    .putStringSet("seenFiles", seen.filter { it in known }.toSet())
                    .putLong("lastSuccess", now).commit())
                status = status.copy(message = "Синхронизация выполнена", lastSuccess = now)
                Log.i(TAG, "sync_ok")
                true
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (problem: Exception) {
                val login = problem is GoogleLoginRequired || problem is CloudHttpException && problem.status == 401
                val message = when {
                    login -> "Необходим повторный вход в Google"
                    !hasNetwork() || problem is java.net.UnknownHostException -> "Нет подключения к интернету"
                    else -> "Не удалось синхронизировать. Данные сохранены на устройстве"
                }
                status = status.copy(message = message)
                Log.w(TAG, if (login) "sync_login_required" else "sync_retry")
                // Invalid permission requires user action; transport/quota failures may retry.
                login
            } finally { status = status.copy(busy = false) }
        }
    }

    private suspend fun freshToken(): String {
        val current = session ?: throw GoogleLoginRequired()
        val auth = AuthState.jsonDeserialize(current.getJSONObject("auth"))
        val token = suspendCancellableCoroutine<String> { continuation ->
            auth.performActionWithFreshTokens(service) { access, _, error ->
                if (continuation.isActive) {
                    if (access != null && error == null) continuation.resume(access)
                    else if (error?.error in setOf("invalid_grant", "invalid_client", "unauthorized_client", "access_denied"))
                        continuation.resumeWithException(GoogleLoginRequired())
                    else continuation.resumeWithException(java.io.IOException("Token refresh unavailable"))
                }
            }
        }
        current.put("auth", auth.jsonSerialize())
        vault.write(current)
        return token
    }

    private fun hasNetwork(): Boolean = runCatching {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        connectivity.getNetworkCapabilities(connectivity.activeNetwork)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }.getOrDefault(false)

    private fun reschedulePeriodic() {
        val manager = WorkManager.getInstance(context)
        if (status.email == null || !status.automatic) { manager.cancelUniqueWork(PERIODIC_WORK); return }
        manager.enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<CloudSyncWorker>(6, TimeUnit.HOURS).setConstraints(networkConstraint())
                .addTag(WORK_TAG).build())
    }

    private fun scheduleSoon() {
        WorkManager.getInstance(context).enqueueUniqueWork(CHANGE_WORK, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<CloudSyncWorker>().setInitialDelay(2, TimeUnit.SECONDS)
                .setConstraints(networkConstraint()).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(WORK_TAG).build())
    }

    private fun scheduleManual() {
        WorkManager.getInstance(context).enqueueUniqueWork(MANUAL_WORK, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<CloudSyncWorker>().setConstraints(networkConstraint())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).addTag(WORK_TAG).build())
    }

    companion object {
        const val DRIVE_SCOPE = "https://www.googleapis.com/auth/drive.appdata"
        const val REDIRECT_URI = "ru.dietdiary.offline:/oauth2redirect"
        private const val TAG = "DietDiaryCloud"
        private const val WORK_TAG = "dietdiary-cloud"
        private const val CHANGE_WORK = "dietdiary-cloud-changes"
        private const val MANUAL_WORK = "dietdiary-cloud-manual"
        private const val PERIODIC_WORK = "dietdiary-cloud-periodic"
        @Volatile private var instance: CloudSync? = null
        fun get(context: Context): CloudSync = instance ?: synchronized(this) {
            instance ?: CloudSync(context.applicationContext).also { instance = it }
        }
        private fun networkConstraint() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    }
}

class CloudSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        if (CloudSync.get(applicationContext).synchronize()) Result.success() else Result.retry()
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { Result.retry() }
}
