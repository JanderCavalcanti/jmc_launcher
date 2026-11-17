package com.openauto.dash

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * jmc_launcher: the Recents screen of the floating button ([FloatingBall]).
 * Android 13's own Recents is the Launcher3 that came with the tbox (its
 * quickstep), gone since JMCAR is the Home app, so the system's Recents key
 * and the firmware's button both do nothing. This one lists the apps used
 * lately: a tap brings one back, the cross closes it, "Close all" closes all
 * but the map inside the dashboard. A tap beside the cards goes back.
 */
class RecentsActivity : ComponentActivity() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLanguage.wrap(base))
    }

    private val entries = mutableStateOf<List<Entry>>(emptyList())
    private val io = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /** One card: the task and what shows it. */
    private class Entry(val task: RecentTasks.Task, val label: String, val icon: Drawable?) {
        /** The map inside the dashboard's tile (its own display): opened, never closed from here. */
        val closable: Boolean get() = task.displayId == 0
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            OpenAutoDashTheme {
                RecentsScreen(
                    entries = entries.value,
                    onOpen = { entry ->
                        finish()
                        RecentTasks.open(applicationContext, entry.task)
                    },
                    onClose = { entry ->
                        if (RecentTasks.remove(entry.task.taskId)) entries.value = entries.value - entry
                    },
                    onCloseAll = {
                        entries.value.filter { it.closable }.forEach { RecentTasks.remove(it.task.taskId) }
                        finish()
                    },
                    onDismiss = { finish() }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    /** Leaving it for any app closes it: it is a moment's screen, not a place to come back to. */
    override fun onStop() {
        super.onStop()
        if (!isFinishing) finish()
    }

    override fun onDestroy() {
        io.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        super.onDestroy()
    }

    private fun reload() {
        io.launch {
            entries.value = withContext(Dispatchers.IO) {
                val pm = packageManager
                val home = RecentTasks.homePackages(this@RecentsActivity)
                RecentTasks.list(this@RecentsActivity)
                    .filter { it.packageName !in home }
                    .distinctBy { it.packageName }
                    .mapNotNull { task ->
                        // Only apps the driver opens: no bare services or the firmware's helpers.
                        if (pm.getLaunchIntentForPackage(task.packageName) == null) return@mapNotNull null
                        val info = runCatching { pm.getApplicationInfo(task.packageName, 0) }.getOrNull() ?: return@mapNotNull null
                        Entry(task, info.loadLabel(pm).toString(), runCatching { info.loadIcon(pm) }.getOrNull())
                    }
            }
        }
    }

    @Composable
    private fun RecentsScreen(
        entries: List<Entry>,
        onOpen: (Entry) -> Unit,
        onClose: (Entry) -> Unit,
        onCloseAll: () -> Unit,
        onDismiss: () -> Unit
    ) {
        val none = remember { MutableInteractionSource() }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xCC000000))
                .clickable(interactionSource = none, indication = null, onClick = onDismiss),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text(getString(R.string.jmc_ball_recents), color = Color.White, fontSize = 20.sp)
                Spacer(Modifier.height(16.dp))
                if (entries.isEmpty()) {
                    Text(getString(R.string.jmc_recents_empty), color = Color(0xFFB0B0B0), fontSize = 15.sp)
                } else {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        items(entries, key = { it.task.taskId }) { entry -> Card(entry, onOpen, onClose) }
                    }
                    Spacer(Modifier.height(20.dp))
                    if (entries.any { it.closable }) {
                        TextButton(onClick = onCloseAll) {
                            Text(getString(R.string.jmc_recents_close_all), color = Color.White, fontSize = 16.sp)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun Card(entry: Entry, onOpen: (Entry) -> Unit, onClose: (Entry) -> Unit) {
        Box(
            modifier = Modifier
                .width(150.dp)
                .height(170.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xFF2A2B2E))
                .clickable { onOpen(entry) }
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxSize().padding(12.dp)
            ) {
                if (entry.icon != null) AppIcon(icon = entry.icon, size = 72.dp)
                Spacer(Modifier.height(12.dp))
                Text(
                    entry.label, color = Color.White, fontSize = 15.sp, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center
                )
            }
            if (entry.closable) {
                Row(Modifier.align(Alignment.TopEnd).padding(6.dp)) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color(0x33FFFFFF))
                            .clickable { onClose(entry) },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.Close, contentDescription = getString(R.string.jmc_recents_close),
                            tint = Color.White, modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }

    companion object {
        fun open(context: Context) {
            runCatching {
                context.startActivity(
                    Intent(context, RecentsActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                )
            }
        }
    }
}
