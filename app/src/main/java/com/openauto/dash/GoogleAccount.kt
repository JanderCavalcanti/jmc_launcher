package com.openauto.dash

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/*
 * jmc_launcher: the driver's Google account, used for one thing only: keeping
 * the setup ([SetupBackup]'s JSON) in one file of their Google Drive
 * (scope drive.file: the app sees only the files it made; the device flow
 * refuses drive.appdata), so it comes back on another unit
 * or after a reset.
 *
 * Sign-in is Google's "TV and limited input" device flow: the unit shows a
 * short code, the driver approves it on their phone at google.com/device. No
 * Google Play services needed and no password typed on the car's screen.
 *
 * The tokens live only in [PREFS], which is never saved in a backup
 * ([SetupBackup.SKIP_FILES], the backup rules).
 */
internal object GoogleAccount {
    private const val TAG = "GoogleAccount"
    const val PREFS = "google_account"
    private const val KEY_REFRESH = "refresh_token"
    private const val KEY_ACCESS = "access_token"
    private const val KEY_ACCESS_UNTIL = "access_until"
    private const val KEY_EMAIL = "email"
    private const val KEY_FILE_ID = "drive_file_id"
    private const val KEY_LAST_SYNC = "last_sync"
    private const val KEY_LAST_HASH = "last_hash"
    // Set at sign-in until the driver has answered whether to restore the setup
    // the account holds: no automatic save meanwhile, or this unit's factory
    // setup could overwrite it.
    private const val KEY_HOLD = "hold_until_answered"

    private const val SCOPE = "openid email https://www.googleapis.com/auth/drive.file"
    private const val DEVICE_URL = "https://oauth2.googleapis.com/device/code"
    private const val TOKEN_URL = "https://oauth2.googleapis.com/token"
    private const val REVOKE_URL = "https://oauth2.googleapis.com/revoke"
    private const val DRIVE_FILES = "https://www.googleapis.com/drive/v3/files"
    private const val DRIVE_UPLOAD = "https://www.googleapis.com/upload/drive/v3/files"
    const val FILE_NAME = "jmc-launcher-setup.json"

    /** False when the build carries no OAuth client: the rows say so instead of failing. */
    val configured: Boolean get() = BuildConfig.GOOGLE_CLIENT_ID.isNotBlank()

    data class State(val email: String?, val lastSync: Long)

    private val _state = MutableStateFlow(State(null, 0L))
    val state: StateFlow<State> = _state

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun refreshState(context: Context) {
        val p = prefs(context)
        _state.value = State(p.getString(KEY_EMAIL, null)?.takeIf { p.contains(KEY_REFRESH) }, p.getLong(KEY_LAST_SYNC, 0L))
    }

    fun signedIn(context: Context): Boolean = prefs(context).contains(KEY_REFRESH)

    // ---- Sign-in (device flow) ----

    /** What the driver sees while signing in: the code to type at [url], until [expiresAt]. */
    data class DeviceCode(val deviceCode: String, val userCode: String, val url: String, val intervalMs: Long, val expiresAt: Long)

    /** Asks Google for a sign-in code. Off the main thread. */
    fun startSignIn(): Result<DeviceCode> = runCatching {
        val json = post(DEVICE_URL, mapOf("client_id" to BuildConfig.GOOGLE_CLIENT_ID, "scope" to SCOPE))
        DeviceCode(
            deviceCode = json.getString("device_code"),
            userCode = json.getString("user_code"),
            url = json.optString("verification_url", "https://www.google.com/device"),
            intervalMs = json.optLong("interval", 5L).coerceAtLeast(1L) * 1000L,
            expiresAt = System.currentTimeMillis() + json.optLong("expires_in", 1800L) * 1000L
        )
    }

    sealed interface Poll {
        data object Waiting : Poll
        data class SlowDown(val extraMs: Long) : Poll
        data class Done(val email: String?) : Poll
        data class Failed(val reason: String) : Poll
    }

    /** One look at whether the driver has approved [code] yet. Off the main thread. */
    fun poll(context: Context, code: DeviceCode): Poll {
        if (System.currentTimeMillis() > code.expiresAt) return Poll.Failed("expired_token")
        val (status, json) = postRaw(
            TOKEN_URL, mapOf(
                "client_id" to BuildConfig.GOOGLE_CLIENT_ID,
                "client_secret" to BuildConfig.GOOGLE_CLIENT_SECRET,
                "device_code" to code.deviceCode,
                "grant_type" to "urn:ietf:params:oauth:grant-type:device_code"
            )
        )
        if (status == HttpURLConnection.HTTP_OK) {
            val email = json.optString("id_token").takeIf { it.isNotBlank() }?.let(::emailOf)
            prefs(context).edit()
                .putString(KEY_REFRESH, json.getString("refresh_token"))
                .putString(KEY_ACCESS, json.getString("access_token"))
                .putLong(KEY_ACCESS_UNTIL, System.currentTimeMillis() + (json.optLong("expires_in", 3600L) - 60L) * 1000L)
                .putString(KEY_EMAIL, email)
                .remove(KEY_FILE_ID).remove(KEY_LAST_HASH)
                .putBoolean(KEY_HOLD, true)
                .apply()
            refreshState(context)
            return Poll.Done(email)
        }
        return when (val error = json.optString("error")) {
            "authorization_pending" -> Poll.Waiting
            "slow_down" -> Poll.SlowDown(5_000L)
            else -> Poll.Failed(error.ifBlank { "http_$status" })
        }
    }

    /** Forgets the account here and asks Google to drop the grant. Off the main thread. */
    fun signOut(context: Context) {
        val token = prefs(context).getString(KEY_REFRESH, null)
        prefs(context).edit().clear().apply()
        refreshState(context)
        if (token != null) runCatching { postRaw(REVOKE_URL, mapOf("token" to token)) }
    }

    /** The e-mail inside an ID token (a JWT): its middle part, base64url JSON. */
    internal fun emailOf(idToken: String): String? = runCatching {
        val payload = idToken.split('.')[1]
        val text = String(Base64.decode(payload, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP), Charsets.UTF_8)
        JSONObject(text).optString("email").takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun accessToken(context: Context): String {
        val p = prefs(context)
        val current = p.getString(KEY_ACCESS, null)
        if (current != null && System.currentTimeMillis() < p.getLong(KEY_ACCESS_UNTIL, 0L)) return current
        val refresh = p.getString(KEY_REFRESH, null) ?: throw IOException("not signed in")
        val (status, json) = postRaw(
            TOKEN_URL, mapOf(
                "client_id" to BuildConfig.GOOGLE_CLIENT_ID,
                "client_secret" to BuildConfig.GOOGLE_CLIENT_SECRET,
                "refresh_token" to refresh,
                "grant_type" to "refresh_token"
            )
        )
        if (status != HttpURLConnection.HTTP_OK) {
            // A revoked or expired grant: signed out, the rows offer to sign in again.
            if (json.optString("error") == "invalid_grant") {
                p.edit().clear().apply()
                refreshState(context)
            }
            throw IOException("token refresh failed: ${json.optString("error", "http_$status")}")
        }
        val token = json.getString("access_token")
        p.edit().putString(KEY_ACCESS, token)
            .putLong(KEY_ACCESS_UNTIL, System.currentTimeMillis() + (json.optLong("expires_in", 3600L) - 60L) * 1000L)
            .apply()
        return token
    }

    // ---- Drive ----

    /** The id of the setup file the app made in Drive, null when there is none yet. */
    private fun findFile(context: Context, token: String): String? {
        prefs(context).getString(KEY_FILE_ID, null)?.let { return it }
        val q = URLEncoder.encode("name = '$FILE_NAME' and trashed = false", "UTF-8")
        val (status, body) = request("GET", "$DRIVE_FILES?spaces=drive&orderBy=modifiedTime%20desc&fields=files(id,modifiedTime)&q=$q", token)
        if (status != HttpURLConnection.HTTP_OK) throw IOException("drive list: http $status")
        val files = JSONObject(body).optJSONArray("files")
        val id = if (files != null && files.length() > 0) files.getJSONObject(0).getString("id") else null
        if (id != null) prefs(context).edit().putString(KEY_FILE_ID, id).apply()
        return id
    }

    /** Whether the account already holds a saved setup. Off the main thread. */
    fun hasRemote(context: Context): Result<Boolean> = runCatching { findFile(context, accessToken(context)) != null }

    /** The driver answered the restore question after sign-in (or there was nothing to restore): saving may start. */
    fun releaseHold(context: Context) {
        prefs(context).edit().remove(KEY_HOLD).apply()
    }

    /** Still waiting for that answer, e.g. Settings closed with the question open. */
    fun onHold(context: Context): Boolean = signedIn(context) && prefs(context).getBoolean(KEY_HOLD, false)

    /** Saves the setup to Drive now. Off the main thread. */
    fun upload(context: Context): Result<Unit> = runCatching {
        val text = SetupBackup.export(context)
        val token = accessToken(context)
        val id = findFile(context, token)
        val status = if (id == null) {
            val boundary = "jmc" + System.nanoTime()
            val meta = JSONObject().put("name", FILE_NAME).put("description", "jmc_launcher: configuração salva pelo launcher").toString()
            val body = "--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$meta\r\n" +
                "--$boundary\r\nContent-Type: application/json\r\n\r\n$text\r\n--$boundary--\r\n"
            val (s, resp) = request("POST", "$DRIVE_UPLOAD?uploadType=multipart&fields=id", token, body, "multipart/related; boundary=$boundary")
            if (s == HttpURLConnection.HTTP_OK) prefs(context).edit().putString(KEY_FILE_ID, JSONObject(resp).getString("id")).apply()
            s
        } else {
            val (s, _) = request("PATCH", "$DRIVE_UPLOAD/$id?uploadType=media", token, text, "application/json")
            // The file was deleted from Drive meanwhile: forget its id, the next save creates it again.
            if (s == HttpURLConnection.HTTP_NOT_FOUND) prefs(context).edit().remove(KEY_FILE_ID).apply()
            s
        }
        if (status != HttpURLConnection.HTTP_OK) throw IOException("drive upload: http $status")
        prefs(context).edit().putLong(KEY_LAST_SYNC, System.currentTimeMillis()).putString(KEY_LAST_HASH, hash(text)).remove(KEY_HOLD).apply()
        refreshState(context)
    }

    /** The setup saved in Drive, as [SetupBackup.restore] takes it. Off the main thread. */
    fun download(context: Context): Result<String> = runCatching {
        val token = accessToken(context)
        val id = findFile(context, token) ?: throw IOException("no saved setup")
        val (status, body) = request("GET", "$DRIVE_FILES/$id?alt=media", token)
        if (status != HttpURLConnection.HTTP_OK) throw IOException("drive download: http $status")
        body
    }

    /** Saves only when the setup changed since the last save. Off the main thread. */
    fun uploadIfChanged(context: Context) {
        if (!configured || !signedIn(context) || prefs(context).getBoolean(KEY_HOLD, false)) return
        val text = runCatching { SetupBackup.export(context) }.getOrNull() ?: return
        if (hash(text) == prefs(context).getString(KEY_LAST_HASH, null)) return
        upload(context).onFailure { Log.w(TAG, "automatic save failed: ${it.message}") }
    }

    private fun hash(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    // ---- HTTP ----

    private fun form(fields: Map<String, String>) =
        fields.entries.joinToString("&") { URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8") }

    private fun post(url: String, fields: Map<String, String>): JSONObject {
        val (status, json) = postRaw(url, fields)
        if (status != HttpURLConnection.HTTP_OK) throw IOException(json.optString("error", "http $status"))
        return json
    }

    private fun postRaw(url: String, fields: Map<String, String>): Pair<Int, JSONObject> {
        val (status, body) = request("POST", url, null, form(fields), "application/x-www-form-urlencoded")
        return status to (runCatching { JSONObject(body) }.getOrNull() ?: JSONObject())
    }

    private fun request(method: String, url: String, token: String?, body: String? = null, contentType: String? = null): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            // HttpURLConnection has no PATCH: Google's override header does the same.
            if (method == "PATCH") {
                c.requestMethod = "POST"
                c.setRequestProperty("X-HTTP-Method-Override", "PATCH")
            } else {
                c.requestMethod = method
            }
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            token?.let { c.setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                c.doOutput = true
                contentType?.let { c.setRequestProperty("Content-Type", it) }
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = c.responseCode
            val stream = if (status in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            return status to text
        } finally {
            c.disconnect()
        }
    }
}

/**
 * Saves the setup to the Google account on its own: a few seconds after the
 * driver leaves a screen, and every 15 minutes while the launcher runs, each
 * time only if something changed.
 */
internal object GoogleSync {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pending: Job? = null

    fun install(app: Application) {
        GoogleAccount.refreshState(app)
        if (!GoogleAccount.configured) return
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityPaused(activity: Activity) = soon(app)
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
        scope.launch {
            while (isActive) {
                delay(15 * 60_000L)
                GoogleAccount.uploadIfChanged(app)
            }
        }
    }

    /** A save in 10 s; a newer call pushes it back, so a burst of changes is one save. */
    fun soon(context: Context) {
        val app = context.applicationContext
        if (!GoogleAccount.signedIn(app)) return
        pending?.cancel()
        pending = scope.launch {
            delay(10_000L)
            GoogleAccount.uploadIfChanged(app)
        }
    }
}
