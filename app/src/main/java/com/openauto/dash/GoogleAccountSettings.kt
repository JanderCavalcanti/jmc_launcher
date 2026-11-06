package com.openauto.dash

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/*
 * Settings, General: the Google account the setup is saved to ([GoogleAccount]).
 * Sign-in is Google's device flow: a code shown here, typed on the phone at
 * google.com/device, so it works on a head unit without Google services.
 */
@Composable
internal fun GoogleAccountRows() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by GoogleAccount.state.collectAsState()
    var code by remember { mutableStateOf<GoogleAccount.DeviceCode?>(null) }
    var askRestore by remember { mutableStateOf(false) }
    var askSignOut by remember { mutableStateOf(false) }
    val failed = stringResource(R.string.google_failed)
    val saved = stringResource(R.string.google_saved)

    // Signed in but the restore question never answered (Settings closed with it open): ask again.
    LaunchedEffect(state.email) {
        if (state.email != null && withContext(Dispatchers.IO) { GoogleAccount.onHold(context) }) askRestore = checkAfterSignIn(context)
    }

    SettingsSection(stringResource(R.string.google_section))
    if (!GoogleAccount.configured) {
        SettingsRow(Icons.Filled.AccountCircle, stringResource(R.string.google_sign_in), stringResource(R.string.google_not_configured)) {}
        Spacer(Modifier.height(20.dp))
        return
    }
    val email = state.email
    if (email == null) {
        SettingsRow(Icons.Filled.AccountCircle, stringResource(R.string.google_sign_in), stringResource(R.string.google_sign_in_detail)) {
            scope.launch {
                withContext(Dispatchers.IO) { GoogleAccount.startSignIn() }
                    .onSuccess { code = it }
                    .onFailure { Toast.makeText(context, failed, Toast.LENGTH_SHORT).show() }
            }
        }
    } else {
        val last = if (state.lastSync > 0L) {
            stringResource(R.string.google_last_sync, DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(state.lastSync)))
        } else {
            stringResource(R.string.google_never_synced)
        }
        SettingsRow(Icons.Filled.AccountCircle, email, last) {}
        SettingsRow(Icons.Filled.CloudUpload, stringResource(R.string.google_save_now), stringResource(R.string.google_save_now_detail)) {
            scope.launch {
                val ok = withContext(Dispatchers.IO) { GoogleAccount.upload(context).isSuccess }
                Toast.makeText(context, if (ok) saved else failed, Toast.LENGTH_SHORT).show()
            }
        }
        SettingsRow(Icons.Filled.CloudDownload, stringResource(R.string.google_restore), stringResource(R.string.google_restore_detail)) {
            askRestore = true
        }
        SettingsRow(Icons.AutoMirrored.Filled.Logout, stringResource(R.string.google_sign_out), null) { askSignOut = true }
    }
    Spacer(Modifier.height(20.dp))

    code?.let { c ->
        // Asks Google every few seconds whether the driver approved the code yet.
        LaunchedEffect(c) {
            var wait = c.intervalMs
            while (true) {
                delay(wait)
                when (val r = withContext(Dispatchers.IO) { GoogleAccount.poll(context, c) }) {
                    GoogleAccount.Poll.Waiting -> Unit
                    is GoogleAccount.Poll.SlowDown -> wait += r.extraMs
                    is GoogleAccount.Poll.Done -> {
                        // Clearing the code ends this effect, so the check runs in the rows' own scope.
                        code = null
                        scope.launch { askRestore = checkAfterSignIn(context) }
                        return@LaunchedEffect
                    }
                    is GoogleAccount.Poll.Failed -> {
                        code = null
                        Toast.makeText(context, failed, Toast.LENGTH_SHORT).show()
                        return@LaunchedEffect
                    }
                }
            }
        }
        AlertDialog(
            modifier = Modifier.keepClearOfWindows(),
            onDismissRequest = { code = null },
            containerColor = DashColors.Card,
            title = { Text(stringResource(R.string.google_code_title), color = DashColors.TextPrimary) },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    QrCode(c.url, Modifier.width(140.dp))
                    Spacer(Modifier.width(20.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.google_code_body, c.url), color = DashColors.TextSecondary)
                        Text(
                            c.userCode,
                            color = DashColors.TextPrimary,
                            fontSize = 30.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(stringResource(R.string.google_code_waiting), color = DashColors.Muted)
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { code = null }) { Text(stringResource(R.string.dash_cancel), color = DashColors.Muted) }
            }
        )
    }

    if (askRestore) {
        AlertDialog(
            modifier = Modifier.keepClearOfWindows(),
            onDismissRequest = { askRestore = false },
            containerColor = DashColors.Card,
            title = { Text(stringResource(R.string.google_restore_title), color = DashColors.TextPrimary) },
            text = { Text(stringResource(R.string.google_restore_body), color = DashColors.TextSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    askRestore = false
                    scope.launch {
                        // Seen through even if Settings is closed meanwhile, like a restore from a file.
                        withContext(NonCancellable) {
                            withContext(Dispatchers.IO) { GoogleAccount.download(context).mapCatching { SetupBackup.restore(context, it).getOrThrow() } }
                                .onSuccess { GoogleAccount.releaseHold(context); SetupBackup.restart(context) }
                                .onFailure { Toast.makeText(context, failed, Toast.LENGTH_SHORT).show() }
                        }
                    }
                }) { Text(stringResource(R.string.google_restore_confirm), color = DashColors.Critical) }
            },
            dismissButton = {
                TextButton(onClick = {
                    askRestore = false
                    // Kept this unit's setup: it is the one saved from now on.
                    scope.launch { withContext(NonCancellable + Dispatchers.IO) { GoogleAccount.releaseHold(context); GoogleAccount.uploadIfChanged(context) } }
                }) { Text(stringResource(R.string.google_restore_keep), color = DashColors.Muted) }
            }
        )
    }

    if (askSignOut) {
        AlertDialog(
            modifier = Modifier.keepClearOfWindows(),
            onDismissRequest = { askSignOut = false },
            containerColor = DashColors.Card,
            title = { Text(stringResource(R.string.google_sign_out), color = DashColors.TextPrimary) },
            text = { Text(stringResource(R.string.google_sign_out_body), color = DashColors.TextSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    askSignOut = false
                    scope.launch { withContext(NonCancellable + Dispatchers.IO) { GoogleAccount.signOut(context) } }
                }) { Text(stringResource(R.string.google_sign_out), color = DashColors.Critical) }
            },
            dismissButton = {
                TextButton(onClick = { askSignOut = false }) { Text(stringResource(R.string.dash_cancel), color = DashColors.Muted) }
            }
        )
    }
}

/**
 * After sign-in: true when the account holds a saved setup, so the restore
 * question is shown. With nothing saved yet, this unit's setup is saved now.
 */
private suspend fun checkAfterSignIn(context: android.content.Context): Boolean = withContext(Dispatchers.IO) {
    val remote = GoogleAccount.hasRemote(context).getOrNull() ?: return@withContext false // offline: asked again later
    if (!remote) GoogleAccount.upload(context)
    remote
}
