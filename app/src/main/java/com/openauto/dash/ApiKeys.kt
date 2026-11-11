package com.openauto.dash

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/*
 * The keys a driver brings for the services Dashwheel asks on their behalf
 * (Settings → Expert → Your API keys). One place to see which are set and to
 * paste a new one, with where to get it as a QR code for the phone. A
 * feature's own page may still take its key too (the AI mechanic's does,
 * with its Test button): both read and write the same store.
 */

/** A service the driver may bring a key of their own for. */
internal enum class ApiService(
    @StringRes val titleRes: Int,
    /** What in Dashwheel uses it. */
    @StringRes val usesRes: Int,
    /** Where to get a key, free, for the QR code and the button. */
    val signUpUrl: String
) {
    GEMINI(R.string.ai_key, R.string.api_keys_gemini_uses, "https://aistudio.google.com/apikey"),
    /** Fuel prices in Germany ([Tankerkoenig]). */
    TANKERKOENIG(R.string.api_keys_tankerkoenig, R.string.api_keys_tankerkoenig_uses, Tankerkoenig.SIGN_UP);
}

internal object ApiKeys {
    private const val PREFS = "api_keys"

    /** Bumped on every change, so a row showing how many are set redraws. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    @Volatile private var appContext: Context? = null

    /** For the feeds that read a key with no context at hand ([current]). */
    fun setContext(context: Context) {
        appContext = context.applicationContext
    }

    /** The key kept for [service], blank for none. */
    fun get(context: Context, service: ApiService): String {
        appContext = context.applicationContext
        return when (service) {
            // The AI mechanic's own settings keep the Gemini key: one store, two pages.
            ApiService.GEMINI -> AiSettings.load(context).apiKey
            ApiService.TANKERKOENIG -> prefs(context).getString(service.name, "").orEmpty()
        }
    }

    /** [get] for a feed with no context at hand; blank until the launcher has started. */
    fun current(service: ApiService): String = appContext?.let { get(it, service) }.orEmpty()

    /** Keeps [key] for [service]; blank removes it. */
    fun set(context: Context, service: ApiService, key: String) {
        val clean = key.trim()
        when (service) {
            ApiService.GEMINI -> {
                AiSettings.save(context, AiSettings.load(context).copy(apiKey = clean))
                AiMechanic.refresh()
            }
            ApiService.TANKERKOENIG -> {
                prefs(context).edit().putString(service.name, clean).apply()
                // The stations around are read again with (or without) the key.
                FuelPriceRepo.forget()
            }
        }
        _version.value++
    }

    /** How many of the services have a key. */
    fun countSet(context: Context): Int = ApiService.entries.count { get(context, it).isNotBlank() }

    /**
     * A key as a row may show it: its first and last few characters, the
     * rest hidden, so the driver knows which key is there without showing it
     * all to a passenger. Short keys are hidden whole.
     */
    fun mask(key: String): String {
        val k = key.trim()
        if (k.isEmpty()) return ""
        if (k.length < 12) return "•".repeat(k.length)
        return k.take(4) + "•".repeat(6) + k.takeLast(3)
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/** Settings → Expert → Your API keys: one field per service, saved on leaving. */
@Composable
internal fun ApiKeysSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val keys = remember { ApiService.entries.associateWith { mutableStateOf(ApiKeys.get(context, it)) } }
    fun close() {
        for ((service, state) in keys) if (state.value.trim() != ApiKeys.get(context, service)) ApiKeys.set(context, service, state.value)
        onDismiss()
    }
    SettingsSheet(
        title = stringResource(R.string.api_keys_title),
        onDismiss = ::close,
        actions = { SheetButton(stringResource(R.string.ai_done), onClick = ::close) }
    ) {
        Text(stringResource(R.string.api_keys_explanation), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
        // JMCAR: Tankerkönig is Germany's fuel prices, nothing for Brazil (Jmcar.STATION_FEED).
        for (service in ApiService.entries.filter { Jmcar.STATION_FEED || it != ApiService.TANKERKOENIG }) {
            Spacer(Modifier.height(16.dp))
            ApiKeyField(service, keys.getValue(service).value) { keys.getValue(service).value = it }
        }
    }
}

/** One service: its name, what uses it, the key hidden unless shown, and where to get one. */
@Composable
private fun ApiKeyField(service: ApiService, value: String, onChange: (String) -> Unit) {
    val context = LocalContext.current
    var shown by remember { mutableStateOf(false) }
    val tap = rememberTapFeedback()
    Label(stringResource(service.titleRes))
    Text(stringResource(service.usesRes), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
    Spacer(Modifier.height(6.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            OutlinedTextField(
                value = value,
                onValueChange = { onChange(it.trim()) },
                singleLine = true,
                visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
                placeholder = { Text(stringResource(R.string.api_keys_paste), color = DashColors.Muted) },
                trailingIcon = {
                    IconButton(onClick = { tap(); shown = !shown }) {
                        Icon(
                            if (shown) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = stringResource(if (shown) R.string.api_keys_hide else R.string.api_keys_show),
                            tint = DashColors.TextSecondary
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = fieldColors()
            )
            Text(
                if (value.isBlank()) stringResource(R.string.api_keys_not_set) else stringResource(R.string.api_keys_set),
                color = if (value.isBlank()) DashColors.Muted else DashColors.Good,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 4.dp)
            )
            Spacer(Modifier.height(8.dp))
            SheetButton(stringResource(R.string.api_keys_get), primary = false) {
                tap()
                context.launchSafely(Intent(Intent.ACTION_VIEW, Uri.parse(service.signUpUrl)))
            }
        }
        Spacer(Modifier.width(16.dp))
        // Where to get it, for the phone: typing a key on a head unit is the hard part, the phone's clipboard the easy one.
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            QrCode(service.signUpUrl, Modifier.size(110.dp).clip(DashShape.Small))
            Text(stringResource(R.string.about_scan), color = DashColors.TextSecondary, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
        }
    }
}
