package com.openauto.dash

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/*
 * The last song a player played, kept across a restart of the unit: the media
 * tile shows it (paused) while no player has a session yet, and its play
 * button starts it again in that player, in the background ([CarMediaController]).
 */
internal object LastTrack {
    private const val PREFS = "last_track"
    private const val KEY_PACKAGE = "package"
    private const val KEY_TITLE = "title"
    private const val KEY_ARTIST = "artist"
    private const val KEY_DURATION = "duration"
    private const val KEY_MEDIA_ID = "media_id"
    private const val ART_FILE = "last_track_art.jpg"

    data class Track(val packageName: String, val title: String, val artist: String, val durationMs: Long, val artwork: Bitmap?, val mediaId: String? = null)

    @Volatile private var cached: Track? = null
    @Volatile private var loaded = false
    /** The track and cover written last, so each is written once. */
    private var savedKey: List<Any?>? = null
    private var savedArt: Bitmap? = null

    fun get(context: Context): Track? {
        if (loaded) return cached
        loaded = true
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val pkg = prefs.getString(KEY_PACKAGE, null) ?: return null
        val title = prefs.getString(KEY_TITLE, null).orEmpty()
        if (title.isBlank()) return null
        val art = runCatching { File(context.filesDir, ART_FILE).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) } }.getOrNull()
        cached = Track(pkg, title, prefs.getString(KEY_ARTIST, null).orEmpty(), prefs.getLong(KEY_DURATION, 0L), art, prefs.getString(KEY_MEDIA_ID, null))
        return cached
    }

    /** A player shows [title]: remembered, with its cover, for the next start. */
    fun note(context: Context, packageName: String, title: String, artist: String, durationMs: Long, artwork: Bitmap?, mediaId: String?) {
        if (title.isBlank() || packageName == context.packageName || HeadUnitMedia.isStock(packageName)) return
        val key = listOf(packageName, title, artist, durationMs, mediaId)
        if (key == savedKey && (artwork == null || artwork === savedArt)) return
        val sameSong = key == savedKey
        savedKey = key
        loaded = true
        val art = artwork ?: cached?.artwork?.takeIf { sameSong }
        cached = Track(packageName, title, artist, durationMs, art, mediaId)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_PACKAGE, packageName)
            .putString(KEY_TITLE, title)
            .putString(KEY_ARTIST, artist)
            .putLong(KEY_DURATION, durationMs)
            .putString(KEY_MEDIA_ID, mediaId)
            .apply()
        if (artwork === savedArt && sameSong) return
        savedArt = artwork
        val file = File(context.filesDir, ART_FILE)
        // Off the main thread: a cover is a few dozen KB to compress and write.
        Thread {
            runCatching {
                if (artwork == null) {
                    if (!sameSong) file.delete()
                } else {
                    file.outputStream().use { artwork.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                }
            }
        }.start()
    }
}
