package com.openauto.dash

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.hypot

/*
 * jmc_launcher: JMCAR's own floating button, over every app.
 *
 * The tbox's firmware has one (com.sd.ball, Settings > Display > Floating
 * button), but with JMCAR as the Home app part of it is dead: its Recents and
 * its split screen go to the Launcher3 that came with the unit (its quickstep
 * recents, its "com.android.launcher3" checks), and its "clear memory" kills
 * every app outside a fixed list, JMCAR included. So JMCAR draws its own.
 *
 * It rides on the accessibility service ([SplitAccessibilityService]): always
 * on, brought back by Android if JMCAR's process goes, and allowed to draw over
 * every app. A tap opens the menu beside it: Back, Home, Recents (JMCAR's own
 * screen, [RecentsActivity]), split screen, mute, clear memory, then the first
 * app shortcuts of the bottom bar ([BarDock]). Dragged, it goes to the nearest
 * side and stays where it was left.
 */
internal object FloatingBall {
    private const val TAG = "FloatingBall"
    private const val PREFS = "jmcar"
    private const val KEY_ON = "floating_ball"
    private const val KEY_X = "floating_ball_x"
    private const val KEY_Y = "floating_ball_y"

    /** Shortcuts of the bottom bar shown in the menu, at most. */
    private const val SHORTCUTS = 4
    /** The menu closes by itself after this long untouched. */
    private const val MENU_CLOSE_MS = 6_000L
    /** The ball, idle: half see-through so it hides less of the app below. */
    private const val IDLE_ALPHA = 0.7f

    private val _on = MutableStateFlow(true)
    /** Whether the ball shows (Settings > Dashboard > Floating button). On by default. */
    val on: StateFlow<Boolean> = _on
    @Volatile private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        _on.value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ON, true)
    }

    fun save(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ON, on).apply()
        loaded = true
        _on.value = on
    }

    private val main = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var watch: Job? = null
    private var service: AccessibilityService? = null
    private var ball: View? = null
    private var ballParams: WindowManager.LayoutParams? = null
    private var menu: View? = null
    private val handler = Handler(Looper.getMainLooper())
    private val closeMenu = Runnable { hideMenu() }
    /** When a touch outside the menu last closed it ([OUTSIDE_GRACE_MS]). */
    private var closedByOutside = 0L
    private const val OUTSIDE_GRACE_MS = 500L

    /** From the service, once bound: the ball follows the setting from now on. */
    fun attach(host: AccessibilityService) {
        service = host
        load(host)
        BarDock.load(host)
        watch?.cancel()
        watch = main.launch { on.collect { if (it) showBall() else hideBall() } }
    }

    /** From the service, as it goes. */
    fun detach() {
        watch?.cancel()
        watch = null
        hideBall()
        service = null
    }

    private fun dp(context: Context, value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), context.resources.displayMetrics).toInt()

    /** A real app overlay takes touches on every ROM; the accessibility one is the fallback. */
    private fun overlayType(context: Context): Int =
        if (Settings.canDrawOverlays(context)) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY

    private fun circle(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showBall() {
        val host = service ?: return
        if (ball != null) return
        val wm = host.getSystemService(WindowManager::class.java) ?: return
        val size = dp(host, 52)
        val view = ImageView(host).apply {
            setImageResource(R.drawable.ic_ball_dot)
            setColorFilter(Color.WHITE)
            val pad = dp(host, 10)
            setPadding(pad, pad, pad, pad)
            background = circle(BALL_COLOR).apply { setStroke(dp(host, 2), 0x55FFFFFF) }
            elevation = dp(host, 6).toFloat()
            alpha = IDLE_ALPHA
            contentDescription = host.getString(R.string.jmc_ball_title)
        }
        val metrics = host.resources.displayMetrics
        val prefs = host.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val params = WindowManager.LayoutParams(
            size, size, overlayType(host),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt(KEY_X, metrics.widthPixels - size - dp(host, 8)).coerceIn(0, (metrics.widthPixels - size).coerceAtLeast(0))
            y = prefs.getInt(KEY_Y, metrics.heightPixels / 4).coerceIn(0, (metrics.heightPixels - size).coerceAtLeast(0))
        }
        view.setOnTouchListener(touch(host, wm, view, params))
        runCatching { wm.addView(view, params) }
            .onSuccess { ball = view; ballParams = params; Log.i(TAG, "shown") }
            .onFailure { Log.e(TAG, "could not show the ball", it) }
    }

    private fun hideBall() {
        hideMenu()
        val view = ball ?: return
        ball = null
        ballParams = null
        runCatching { service?.getSystemService(WindowManager::class.java)?.removeView(view) }
    }

    /**
     * A tap opens or closes the menu; a finger that moves farther than a jitter
     * drags the ball, which then goes to the nearest side and is remembered
     * there. A car screen jitters: a tap wobbling a few pixels is still a tap.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun touch(host: Context, wm: WindowManager, view: View, params: WindowManager.LayoutParams): View.OnTouchListener {
        val slop = ViewConfiguration.get(host).scaledTouchSlop * 2
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        return View.OnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY
                    startX = params.x; startY = params.y
                    dragging = false
                    view.alpha = 1f
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!dragging && hypot(dx, dy) > slop) {
                        dragging = true
                        hideMenu()
                    }
                    if (dragging) {
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        runCatching { wm.updateViewLayout(view, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        toSide(host, wm, view, params)
                        host.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                            .putInt(KEY_X, params.x).putInt(KEY_Y, params.y).apply()
                    } else if (event.actionMasked == MotionEvent.ACTION_UP) {
                        // The menu was just closed by this very touch (outside it): stays closed.
                        val justClosed = android.os.SystemClock.uptimeMillis() - closedByOutside < OUTSIDE_GRACE_MS
                        if (menu == null) { if (!justClosed) showMenu() } else hideMenu()
                    }
                    if (menu == null) view.alpha = IDLE_ALPHA
                    dragging = false
                    true
                }
                else -> false
            }
        }
    }

    /** Let go, the ball goes to the side it is nearer to, kept on the screen. */
    private fun toSide(host: Context, wm: WindowManager, view: View, params: WindowManager.LayoutParams) {
        val metrics = host.resources.displayMetrics
        val size = params.width
        val edge = dp(host, 4)
        params.x = if (params.x + size / 2 < metrics.widthPixels / 2) edge else metrics.widthPixels - size - edge
        params.y = params.y.coerceIn(0, (metrics.heightPixels - size).coerceAtLeast(0))
        runCatching { wm.updateViewLayout(view, params) }
    }

    /** One button of the menu: its icon over its name. */
    private class Item(val label: String, val icon: Drawable?, val tint: Boolean, val run: () -> Unit)

    private fun items(host: AccessibilityService): List<Item> {
        val list = mutableListOf(
            Item(host.getString(R.string.jmc_ball_back), host.getDrawable(R.drawable.ic_ball_back), true) {
                host.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            },
            Item(host.getString(R.string.jmc_ball_home), host.getDrawable(R.drawable.ic_ball_home), true) { goHome(host) },
            Item(host.getString(R.string.jmc_ball_recents), host.getDrawable(R.drawable.ic_ball_recents), true) {
                RecentsActivity.open(host)
            },
            Item(host.getString(R.string.jmc_ball_split), host.getDrawable(R.drawable.ic_ball_split), true) {
                main.launch { BallSplit.toggle(host) }
            },
            Item(host.getString(R.string.jmc_ball_mute), host.getDrawable(R.drawable.ic_ball_mute), true) {
                MediaVolume.toggleMute(host, show = true)
            },
            Item(host.getString(R.string.jmc_ball_clean), host.getDrawable(R.drawable.ic_ball_clean), true) {
                main.launch { MemoryCleaner.clean(host) }
            }
        )
        val pm = host.packageManager
        BarDock.packages.value.take(SHORTCUTS).forEach { key ->
            val pkg = AppLauncher.packageOf(key)
            val info = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull() ?: return@forEach
            list += Item(info.loadLabel(pm).toString(), runCatching { info.loadIcon(pm) }.getOrNull(), false) {
                AppLauncher.launch(host, key)
            }
        }
        return list
    }

    /** JMCAR's dashboard, as the unit's own Home key opens it. */
    private fun goHome(context: Context) {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            .setPackage(context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        runCatching { context.startActivity(home) }
            .onFailure {
                Log.w(TAG, "Home by intent failed", it)
                (context as? AccessibilityService)?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showMenu() {
        val host = service ?: return
        val params0 = ballParams ?: return
        if (menu != null) return
        val wm = host.getSystemService(WindowManager::class.java) ?: return
        val metrics = host.resources.displayMetrics
        val cell = dp(host, 64)
        val row = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            val pad = dp(host, 6)
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                cornerRadius = dp(host, 20).toFloat()
                setColor(MENU_COLOR)
                setStroke(dp(host, 1), 0x33FFFFFF)
            }
            elevation = dp(host, 8).toFloat()
        }
        items(host).forEach { item ->
            val column = LinearLayout(host).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                background = host.getDrawable(android.R.drawable.list_selector_background)
                setOnClickListener {
                    hideMenu()
                    runCatching { item.run() }.onFailure { Log.w(TAG, "${item.label} failed", it) }
                }
                contentDescription = item.label
            }
            column.addView(ImageView(host).apply {
                setImageDrawable(item.icon)
                if (item.tint) setColorFilter(Color.WHITE)
            }, LinearLayout.LayoutParams(dp(host, 30), dp(host, 30)))
            column.addView(TextView(host).apply {
                text = item.label
                setTextColor(Color.WHITE)
                textSize = 11f
                maxLines = 1
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(host, 4)
            })
            row.addView(column, LinearLayout.LayoutParams(cell, cell + dp(host, 6)))
        }
        row.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val width = row.measuredWidth.coerceAtMost(metrics.widthPixels)
        val height = row.measuredHeight
        val gap = dp(host, 8)
        val onLeft = params0.x + params0.width / 2 < metrics.widthPixels / 2
        val params = WindowManager.LayoutParams(
            width, height, overlayType(host),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (if (onLeft) params0.x + params0.width + gap else params0.x - width - gap)
                .coerceIn(0, (metrics.widthPixels - width).coerceAtLeast(0))
            y = (params0.y + params0.height / 2 - height / 2).coerceIn(0, (metrics.heightPixels - height).coerceAtLeast(0))
        }
        // A touch anywhere else closes the menu.
        row.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                closedByOutside = android.os.SystemClock.uptimeMillis()
                hideMenu()
            }
            false
        }
        runCatching { wm.addView(row, params) }
            .onSuccess {
                menu = row
                ball?.alpha = 1f
                handler.removeCallbacks(closeMenu)
                handler.postDelayed(closeMenu, MENU_CLOSE_MS)
            }
            .onFailure { Log.e(TAG, "could not show the menu", it) }
    }

    private fun hideMenu() {
        handler.removeCallbacks(closeMenu)
        val view = menu ?: return
        menu = null
        ball?.alpha = IDLE_ALPHA
        runCatching { service?.getSystemService(WindowManager::class.java)?.removeView(view) }
    }

    private val BALL_COLOR = 0xE01A73E8.toInt()
    private val MENU_COLOR = 0xF0202124.toInt()
}

/**
 * The split screen from the floating button. Android 13's own way in (the
 * recents of the unit's Launcher3, its quickstep) is gone with that launcher,
 * and the firmware's split broadcast reaches a SystemUI hook that does
 * nothing. What is left is the window manager shell's own command, through
 * the privileged shell: the app in front stays, and the last app used before
 * it comes beside it. Pressed again while split, the side app leaves.
 *
 * The command takes only a task that runs on the main screen: a task id that
 * is only in the recents list made SystemUI crash (2026-11-16), so only
 * running tasks of display 0 are ever handed to it.
 */
internal object BallSplit {
    private const val TAG = "BallSplit"
    private const val WM_SHELL = "dumpsys activity service SystemUIService WMShell splitscreen"
    /** Right (landscape) or bottom: the app in front keeps the left. */
    private const val SIDE_RIGHT = 1
    private val REFUSED = Regex("Exception|Error|Unknown|not found|denied", RegexOption.IGNORE_CASE)

    /** The task this button put beside the other one, taken out again on the next press. */
    @Volatile private var sideTask: Int? = null

    suspend fun toggle(context: Context) {
        if (!PrivilegedShell.access.value.shell) {
            toast(context, R.string.jmc_ball_split_no_shell)
            return
        }
        val side = sideTask
        if (side != null && SplitAccessibilityService.isSplit()) {
            sideTask = null
            runCatching { DockShell.shell(context, "$WM_SHELL removeFromSideStage $side") }
                .onFailure { Log.w(TAG, "could not leave the split", it) }
            return
        }
        sideTask = null
        val tasks = withContext(Dispatchers.IO) { RecentTasks.list(context) }
        val home = RecentTasks.homePackages(context)
        // The dashboard in front: there is no app to keep on the left.
        val top = tasks.firstOrNull { it.running && it.displayId == 0 }
        if (top == null || top.packageName in home) {
            toast(context, R.string.jmc_ball_split_need_app)
            return
        }
        val onScreen = tasks.filter { it.running && it.displayId == 0 && it.packageName !in home }
        val front = onScreen.firstOrNull()
        val other = onScreen.drop(1).firstOrNull { it.packageName != front?.packageName }
        if (front == null || other == null) {
            toast(context, R.string.jmc_ball_split_need_two)
            return
        }
        Log.i(TAG, "split: ${front.packageName} (task ${front.taskId}) with ${other.packageName} (task ${other.taskId})")
        // The ADB shell gives no error code: a refusal is only in what it printed.
        val answer = runCatching { DockShell.shell(context, "$WM_SHELL moveToSideStage ${other.taskId} $SIDE_RIGHT") }
            .onFailure { Log.w(TAG, "split refused", it) }
            .getOrNull()
        if (answer == null || REFUSED.containsMatchIn(answer)) {
            Log.w(TAG, "split refused: ${answer.orEmpty().take(300)}")
            toast(context, R.string.jmc_ball_split_failed)
        } else {
            sideTask = other.taskId
        }
    }

    private suspend fun toast(context: Context, text: Int) = withContext(Dispatchers.Main) {
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    }
}

/**
 * The tasks Android keeps, newest first, every app's: JMCAR is signed with the
 * unit's platform key, so REAL_GET_TASKS is granted and the list is not only
 * its own. For the Recents screen and the split.
 */
internal object RecentTasks {
    private const val TAG = "RecentTasks"

    data class Task(
        val taskId: Int,
        val packageName: String,
        val running: Boolean,
        val displayId: Int,
        val intent: Intent?
    )

    @Suppress("DEPRECATION")
    fun list(context: Context, max: Int = 30): List<Task> {
        val am = context.getSystemService(ActivityManager::class.java) ?: return emptyList()
        val tasks = runCatching { am.getRecentTasks(max, ActivityManager.RECENT_IGNORE_UNAVAILABLE) }
            .onFailure { Log.w(TAG, "no recent tasks", it) }
            .getOrNull().orEmpty()
        return tasks.mapNotNull { info ->
            val pkg = info.baseActivity?.packageName ?: info.baseIntent?.component?.packageName
                ?: info.baseIntent?.`package` ?: return@mapNotNull null
            Task(
                taskId = info.taskId,
                packageName = pkg,
                running = info.isRunning,
                displayId = runCatching { info.javaClass.getField("displayId").getInt(info) }.getOrDefault(0),
                intent = info.baseIntent
            )
        }
    }

    /** The Home apps (JMCAR, the firmware's fallback ones): never in Recents nor the split. */
    fun homePackages(context: Context): Set<String> {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val found = runCatching {
            context.packageManager.queryIntentActivities(home, 0).map { it.activityInfo.packageName }
        }.getOrDefault(emptyList())
        return (found + context.packageName + "com.android.settings.FallbackHome").toSet()
    }

    /** Brings [task] back to the screen; an app that no longer runs there is opened again. */
    fun open(context: Context, task: Task) {
        val am = context.getSystemService(ActivityManager::class.java)
        if (task.running && task.displayId == 0 &&
            runCatching { am.moveTaskToFront(task.taskId, 0); true }.getOrDefault(false)
        ) return
        if (AppLauncher.launch(context, task.packageName)) return
        task.intent?.let { intent ->
            runCatching { context.startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                .onFailure { Log.w(TAG, "could not open ${task.packageName}", it) }
        }
    }

    /** Takes [taskId] out of Recents and closes its app's screens (REMOVE_TASKS, platform key). */
    @SuppressLint("PrivateApi")
    fun remove(taskId: Int): Boolean = runCatching {
        val atm = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)
        atm.javaClass.getMethod("removeTask", Int::class.javaPrimitiveType).invoke(atm, taskId) as Boolean
    }.onFailure { Log.w(TAG, "could not remove task $taskId", it) }.getOrDefault(false)
}

/**
 * The floating button's "clear memory". The firmware's own (com.sd.ball)
 * kills every process outside a fixed list, JMCAR among them, and with it the
 * accessibility service, the OBD link and the music tile. This one only
 * closes the apps the driver opened on the main screen, as Recents' "Close
 * all" does, and leaves alone JMCAR, the Home apps, the map inside the
 * dashboard (its own display) and the app playing music right now.
 */
internal object MemoryCleaner {
    private const val TAG = "MemoryCleaner"

    suspend fun clean(context: Context) {
        val closed = withContext(Dispatchers.IO) {
            val home = RecentTasks.homePackages(context)
            val playing = playingPackages(context)
            val am = context.getSystemService(ActivityManager::class.java)
            val targets = RecentTasks.list(context)
                .filter { it.displayId == 0 && it.packageName !in home && it.packageName !in playing }
            targets.forEach { RecentTasks.remove(it.taskId) }
            val packages = targets.map { it.packageName }.distinct()
            packages.forEach { pkg -> runCatching { am?.killBackgroundProcesses(pkg) } }
            Log.i(TAG, "closed ${packages.joinToString()} (kept playing: ${playing.joinToString()})")
            packages.size
        }
        withContext(Dispatchers.Main) {
            Toast.makeText(
                context,
                if (closed == 0) context.getString(R.string.jmc_ball_clean_none)
                else context.resources.getQuantityString(R.plurals.jmc_ball_clean_done, closed, closed),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /** The apps whose media plays now (notification access, as the music tile reads them). */
    private fun playingPackages(context: Context): Set<String> = runCatching {
        val sessions = context.getSystemService(android.media.session.MediaSessionManager::class.java)
        sessions.getActiveSessions(android.content.ComponentName(context, MediaNotificationListenerService::class.java))
            .filter { it.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING }
            .mapNotNull { it.packageName }
            .toSet()
    }.getOrDefault(emptySet())
}
