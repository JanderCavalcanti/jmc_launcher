package com.openauto.dash

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/*
 * JMCAR's app shortcuts live in the bottom bar, between the dashboards'
 * position and the OBD pill, instead of a launch bar tile across the bottom
 * of the dashboard: the tiles above (Google Maps first) get that row.
 * A layout's launch bar tile is taken out as it loads ([Jmcar.dockToBar]),
 * its apps kept here, and the tiles that stood on it grow down by its height.
 */
internal object BarDock {
    private const val PREFS = "jmcar"
    private const val KEY = "bar_dock"

    private val _packages = MutableStateFlow<List<String>>(emptyList())
    val packages: StateFlow<List<String>> = _packages
    @Volatile private var loaded = false

    /** The dashboard's installed apps by package, for the icons. */
    var apps by mutableStateOf<Map<String, AppEntry>>(emptyMap())
    /** Opens an app the way the dashboard does (AppLauncher). */
    var launch: (String) -> Unit = {}
    /** Whether the dashboard shows the shortcuts' editor. */
    var editing by mutableStateOf(false)

    /** The shortcuts saved, or null when none ever were (no launch bar moved here yet). */
    fun stored(context: Context): List<String>? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return null
        return raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        _packages.value = stored(context).orEmpty()
    }

    fun save(context: Context, packages: List<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, packages.joinToString(",")).apply()
        loaded = true
        _packages.value = packages
    }
}

/** The shortcuts in the bar: as many as fit, then the pencil that edits them. */
@Composable
internal fun BarDockRow(modifier: Modifier = Modifier) {
    val packages by BarDock.packages.collectAsState()
    val tap = rememberTapFeedback()
    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        val slot = 50.dp
        val fits = ((maxWidth - slot) / slot).toInt().coerceAtLeast(0)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            packages.take(fits).forEach { pkg ->
                val app = BarDock.apps[pkg]
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(DashColors.CardHi)
                        .clickable(role = Role.Button, onClickLabel = app?.label ?: pkg) { tap(); BarDock.launch(pkg) },
                    contentAlignment = Alignment.Center
                ) {
                    if (app != null) AppIcon(icon = app.icon, size = 34.dp)
                    else Icon(Icons.Filled.Apps, contentDescription = null, tint = DashColors.Muted, modifier = Modifier.size(24.dp))
                }
            }
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .clickable(role = Role.Button) { tap(); BarDock.editing = true },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Edit,
                    contentDescription = stringResource(R.string.apps_launch_bar_edit),
                    tint = DashColors.Muted,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}
