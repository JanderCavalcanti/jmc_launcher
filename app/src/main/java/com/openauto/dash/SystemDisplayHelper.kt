package com.openauto.dash

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.system.Os
import android.util.Log
import android.view.Surface
import kotlin.system.exitProcess

/**
 * The tile displays' owner, as the system user. Runs in a process of its own
 * started through root (`app_process`, see [SystemDisplays]).
 *
 * Android 10 lets an app open a screen on a virtual display only when the
 * display is its own, the system's, or the app declares `allowEmbedded`. The
 * first screen of an app in a tile gets there because Dashwheel opens it
 * with a system permission; every screen the app opened from there by
 * itself (Waze's trip overview, its search results, its voice prompt) was
 * refused the display and thrown full screen onto the main screen, task and
 * all. A display owned by the system user keeps every screen of the apps on
 * it. From Android 12 the display must be a trusted one instead, a flag only
 * the system user (or ADD_TRUSTED_DISPLAY) may set: added here on 11 and
 * later ([systemFlags]; 11 sets it by itself for the system user).
 *
 * Started as root, drops to the system user, makes and sizes the displays
 * Dashwheel asks for over Binder, and hands its Binder to Dashwheel in a
 * broadcast carrying the token it was started with. Exits when Dashwheel's
 * process dies, releasing its displays with it (their apps close, as they do
 * when Dashwheel's own displays go), or after [HELLO_TIMEOUT_MS] when
 * Dashwheel never said hello.
 */
object SystemDisplayHelper {
    private const val TAG = "SystemDisplayHelper"

    const val ACTION = "com.openauto.dash.SYSTEM_DISPLAYS"
    const val EXTRA_BINDER = "binder"
    const val EXTRA_TOKEN = "token"
    const val DESCRIPTOR = "com.openauto.dash.SystemDisplays"

    const val HELLO = 1
    const val CREATE = 2
    const val RESIZE = 3
    const val SURFACE = 4
    const val RELEASE = 5
    const val PING = 6

    private const val SYSTEM_UID = 1000

    /**
     * How long the helper waits for Dashwheel's hello before it leaves. Without
     * one nothing would ever end it: a launcher killed while it started, or a
     * broadcast that came too late for it, left a system JVM running until the
     * next reboot, one more each time the launcher restarted.
     */
    private const val HELLO_TIMEOUT_MS = 120_000L
    private const val BROADCAST = "broadcastIntent"
    private const val BROADCAST_WITH_FEATURE = "broadcastIntentWithFeature"

    /** DisplayManager.VIRTUAL_DISPLAY_FLAG_TRUSTED (@hide, Android 11+): apps may open their screens on it. */
    private const val FLAG_TRUSTED = 1 shl 10

    /** Display.FLAG_TRUSTED (@hide): what the display reports once made. */
    private const val DISPLAY_FLAG_TRUSTED = 1 shl 7

    @JvmStatic
    fun main(args: Array<String>) {
        val token = args.getOrNull(0).orEmpty()
        val pkg = args.getOrNull(1) ?: BuildConfig.APPLICATION_ID
        try {
            run(token, pkg, selfTest = "--self-test" in args)
        } catch (e: Throwable) {
            Log.e(TAG, "died", e)
            System.err.println("SystemDisplayHelper: $e")
            exitProcess(1)
        }
    }

    private fun run(token: String, pkg: String, selfTest: Boolean) {
        Looper.prepareMainLooper()
        Os.setgid(SYSTEM_UID)
        Os.setuid(SYSTEM_UID)
        val context = systemContext()
        val dm = context.getSystemService(DisplayManager::class.java) ?: error("no DisplayManager")
        val server = Server(dm)
        if (selfTest) {
            selfTest(dm)
        } else {
            val intent = Intent(ACTION).setPackage(pkg).putExtras(Bundle().apply {
                putBinder(EXTRA_BINDER, server)
                putString(EXTRA_TOKEN, token)
            // The foreground queue: on the background one it reached Dashwheel 19 s late on the
            // K706, after the tiles had started on displays of their own and had to start again.
            }).addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            broadcast(intent)
            Log.i(TAG, "up as uid ${Os.getuid()}, handed to $pkg")
            Handler(Looper.getMainLooper()).postDelayed({
                if (!server.greeted) {
                    Log.i(TAG, "no hello from $pkg in ${HELLO_TIMEOUT_MS / 1000} s: leaving")
                    exitProcess(0)
                }
            }, HELLO_TIMEOUT_MS)
        }
        Looper.loop()
    }

    /** The system's own context: the package "android", which the system user owns. */
    private fun systemContext(): Context {
        val thread = Class.forName("android.app.ActivityThread").getMethod("systemMain").invoke(null)!!
        return thread.javaClass.getMethod("getSystemContext").invoke(thread) as Context
    }

    /** [intent] to Dashwheel, straight through the activity manager (there is no app context to send it from). */
    private fun broadcast(intent: Intent) {
        val am = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null)!!
        val method = broadcastMethod(am.javaClass.methods.map { it.name }).let { name ->
            am.javaClass.methods.filter { it.name == name }.maxByOrNull { it.parameterTypes.size }
        } ?: error("no broadcast call on IActivityManager")
        val args = broadcastArguments(method.parameterTypes.toList(), intent, userId = 0)
        Log.i(TAG, "${method.name} with ${args.size} arguments")
        method.invoke(am, *args)
    }

    /**
     * IActivityManager's broadcast call on this release, by name: Android 11
     * added `broadcastIntentWithFeature` (a calling feature id after the
     * caller) and kept `broadcastIntent` only for older callers. Pure, for the tests.
     */
    internal fun broadcastMethod(methodNames: Collection<String>): String =
        if (BROADCAST_WITH_FEATURE in methodNames) BROADCAST_WITH_FEATURE else BROADCAST

    /**
     * The arguments of a broadcast call with [parameterTypes], filled by type
     * rather than by position, since the list grew with every release (13 on
     * Android 10, 14 on 11, 15 on 12, 16 from 13: feature id, excluded
     * permissions, excluded packages). The [intent] goes where an Intent is
     * taken; the ints are -1 (no result code, no app op) but the last, the
     * [userId]; the first boolean (serialized: in order) is true, the second
     * (sticky) false; everything else (caller, feature id, resolved type,
     * receiver, result data, bundles, permission lists) null. Pure, for the tests.
     */
    internal fun broadcastArguments(parameterTypes: List<Class<*>>, intent: Any, userId: Int): Array<Any?> {
        val lastInt = parameterTypes.indexOfLast { it == Int::class.javaPrimitiveType }
        var booleans = 0
        return parameterTypes.mapIndexed { i, type ->
            when {
                type == Intent::class.java -> intent
                type == Int::class.javaPrimitiveType -> if (i == lastInt) userId else -1
                type == Boolean::class.javaPrimitiveType -> booleans++ == 0
                else -> null
            }
        }.toTypedArray()
    }

    /**
     * [flags] for a display made as the system user on Android [sdk]: from
     * 11 on, trusted as well, which Android 12 and later ask of a display for
     * an app to open its own screens on it (the system user may set it; 11
     * sets it by itself). Pure, for the tests.
     */
    internal fun systemFlags(sdk: Int, flags: Int): Int =
        if (sdk >= Build.VERSION_CODES.R) flags or FLAG_TRUSTED else flags

    /** A display drawn into a reader nobody looks at, its id printed: to try the launches on it from a shell. */
    @SuppressLint("WrongConstant") // DESTROY_CONTENT_ON_REMOVAL is a real flag, only @hide.
    private fun selfTest(dm: DisplayManager) {
        val reader = ImageReader.newInstance(522, 638, PixelFormat.RGBA_8888, 2)
        reader.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, Handler(Looper.getMainLooper()))
        val vd = dm.createVirtualDisplay("Dashwheel:test", 522, 638, 160, reader.surface, systemFlags(Build.VERSION.SDK_INT, SystemDisplays.FLAGS))
            ?: error("no display")
        val trusted = vd.display.flags and DISPLAY_FLAG_TRUSTED != 0
        println("display ${vd.display.displayId} as uid ${Os.getuid()} on Android ${Build.VERSION.SDK_INT}${if (trusted) ", trusted" else ""}")
        System.out.flush()
    }

    private class Server(private val dm: DisplayManager) : Binder() {
        private val displays = HashMap<Int, VirtualDisplay>()
        private val surfaces = HashMap<Int, Surface?>()

        /** Whether a Dashwheel said hello and is watched: until then the helper leaves after [HELLO_TIMEOUT_MS]. */
        @Volatile var greeted = false

        @SuppressLint("WrongConstant") // DESTROY_CONTENT_ON_REMOVAL is a real flag, only @hide.
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code !in HELLO..PING) return super.onTransact(code, data, reply, flags)
            data.enforceInterface(DESCRIPTOR)
            try {
                when (code) {
                    HELLO -> {
                        val client = data.readStrongBinder() ?: throw IllegalStateException("hello without a client")
                        try {
                            client.linkToDeath({
                                Log.i(TAG, "Dashwheel went: releasing ${displays.size} display(s)")
                                releaseAll()
                                exitProcess(0)
                            }, 0)
                        } catch (e: android.os.RemoteException) {
                            Log.i(TAG, "Dashwheel went before its hello came")
                            releaseAll()
                            exitProcess(0)
                        }
                        greeted = true
                        reply?.writeNoException()
                    }
                    CREATE -> {
                        val name = data.readString() ?: "Dashwheel"
                        val width = data.readInt()
                        val height = data.readInt()
                        val dpi = data.readInt()
                        val displayFlags = systemFlags(Build.VERSION.SDK_INT, data.readInt())
                        val surface = readSurface(data)
                        val vd = dm.createVirtualDisplay(name, width, height, dpi, surface, displayFlags) ?: error("no display for $name")
                        val id = vd.display.displayId
                        synchronized(displays) {
                            displays[id] = vd
                            surfaces[id] = surface
                        }
                        Log.i(TAG, "display $id: $name ${width}x$height at $dpi dpi")
                        reply?.writeNoException()
                        reply?.writeInt(id)
                    }
                    RESIZE -> {
                        val id = data.readInt()
                        val width = data.readInt()
                        val height = data.readInt()
                        val dpi = data.readInt()
                        find(id).resize(width, height, dpi)
                        reply?.writeNoException()
                    }
                    SURFACE -> {
                        val id = data.readInt()
                        val surface = readSurface(data)
                        val vd = find(id)
                        vd.surface = surface
                        synchronized(displays) { surfaces.put(id, surface) }?.release()
                        reply?.writeNoException()
                    }
                    RELEASE -> {
                        val id = data.readInt()
                        val vd = synchronized(displays) { surfaces.remove(id)?.release(); displays.remove(id) }
                        vd?.release()
                        Log.i(TAG, "display $id released")
                        reply?.writeNoException()
                    }
                    PING -> {
                        reply?.writeNoException()
                        reply?.writeInt(Os.getuid())
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "transaction $code failed", e)
                reply?.writeException(IllegalStateException(e.toString()))
            }
            return true
        }

        private fun find(id: Int): VirtualDisplay =
            synchronized(displays) { displays[id] } ?: throw IllegalStateException("no display $id")

        private fun readSurface(data: Parcel): Surface? =
            if (data.readInt() != 0) Surface.CREATOR.createFromParcel(data) else null

        private fun releaseAll() {
            val all = synchronized(displays) { displays.values.toList().also { displays.clear(); surfaces.clear() } }
            all.forEach { runCatching { it.release() } }
        }
    }
}
