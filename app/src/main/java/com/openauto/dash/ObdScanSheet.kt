package com.openauto.dash

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * "What my car answers": a button that asks the car for every standard
 * reading it serves, and the list, with the value read and whether the
 * dashboard already shows it.
 */
@Composable
internal fun ObdScanSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by ObdScan.state.collectAsState()
    val connection by ObdBluetoothManager.connectionState.collectAsState()

    SettingsSheet(
        title = stringResource(R.string.scan_title),
        onDismiss = onDismiss,
        actions = { SheetButton(stringResource(R.string.ai_done), onClick = onDismiss) }
    ) {
        Text(stringResource(R.string.scan_explanation), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)

        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                enabled = !state.running && connection == ObdConnectionState.CONNECTED,
                onClick = { scope.launch { ObdScan.run(context.applicationContext) } },
                colors = buttonColors()
            ) { Text(stringResource(R.string.scan_start)) }
            Spacer(Modifier.size(12.dp))
            when {
                state.running -> {
                    CircularProgressIndicator(color = DashColors.Accent, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.scan_progress, state.step, state.steps), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                }
                connection != ObdConnectionState.CONNECTED ->
                    Text(stringResource(R.string.vehicle_obd_not_connected), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                state.error != null ->
                    Text(stringResource(state.error!!), color = DashColors.Warning, style = MaterialTheme.typography.bodySmall)
            }
        }

        if (state.rows.isNotEmpty()) {
            val readable = state.rows.count { it.value != null }
            Label(stringResource(R.string.scan_found, state.rows.size, readable))
            listOfNotNull(
                state.protocol?.let { stringResource(R.string.scan_protocol, it) },
                state.adapterVolts?.let { stringResource(R.string.scan_adapter_volts, it) }
            ).takeIf { it.isNotEmpty() }?.let {
                Text(it.joinToString(" · "), color = DashColors.Muted, style = MaterialTheme.typography.labelSmall)
            }
            state.rows.forEach { r -> ScanRowLine(r) }
            if (!state.running) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { copy(context, state) }) { Text(stringResource(R.string.scan_copy), color = DashColors.Accent) }
                    state.savedTo?.let {
                        Text(stringResource(R.string.scan_saved, it), color = DashColors.Muted, style = MaterialTheme.typography.labelSmall,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun ScanRowLine(r: ScanRow) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                r.known?.let { stringResource(it.nameRes) } ?: stringResource(R.string.scan_unknown),
                color = DashColors.TextPrimary, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text(
                "01" + hex(r.pid) + " · " + stringResource(if (r.known?.shown == true) R.string.scan_shown else R.string.scan_not_shown) +
                    (if (r.value == null) " · " + (r.reply ?: stringResource(R.string.explore_silent)) else ""),
                color = DashColors.Muted, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            ObdScanRules.valueText(r),
            color = if (r.value != null) DashColors.Good else DashColors.Muted, fontWeight = FontWeight.Bold
        )
    }
}

private fun copy(context: Context, state: ScanState) {
    val text = ObdScanRules.report(state) { context.getString(it.nameRes) }
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("OBD", text))
    Toast.makeText(context, R.string.scan_copied, Toast.LENGTH_SHORT).show()
}
