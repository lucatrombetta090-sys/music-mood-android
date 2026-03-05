package com.musicmood

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Salva e carica la libreria analizzata in formato JSON
 * su storage interno (filesDir/songs_cache.json).
 * Salva anche le preferenze: cartella scelta, filtri, ultima canzone.
 */
object SongCache {

    private const val CACHE_FILE   = "songs_cache.json"
    private const val PREFS_NAME   = "musicmood_prefs"
    private const val KEY_FOLDER   = "scan_folder"
    private const val KEY_VERSION  = "cache_version"
    private const val CACHE_VER    = 4   // incrementa per invalidare cache vecchie

    // ── Salva lista brani ──────────────────────────────────────────────────────

    fun save(context: Context, songs: List<Song>) {
        if (songs.isEmpty()) return
        try {
            val arr = JSONArray()
            songs.forEach { s ->
                arr.put(JSONObject().apply {
                    put("path",          s.path)
                    put("title",         s.title)
                    put("artist",        s.artist)
                    put("album",         s.album)
                    put("genre",         s.genre)
                    put("year",          s.year)
                    put("duration",      s.duration.toDouble())
                    put("albumId",       s.albumId)
                    put("tempo",         s.tempo.toDouble())
                    put("energy",        s.energy.toDouble())
                    put("mood",          s.mood)
                    put("genreResolved", s.genreResolved)
                    put("analyzed",      s.analyzed)
                })
            }
            val root = JSONObject()
            root.put("version", CACHE_VER)
            root.put("songs", arr)
            File(context.filesDir, CACHE_FILE).writeText(root.toString())
        } catch (_: Exception) {}
    }

    // ── Carica lista brani ─────────────────────────────────────────────────────

    fun load(context: Context): List<Song> {
        return try {
            val file = File(context.filesDir, CACHE_FILE)
            if (!file.exists()) return emptyList()
            val root = JSONObject(file.readText())
            if (root.optInt("version") != CACHE_VER) return emptyList()
            val arr = root.getJSONArray("songs")
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Song(
                    path          = o.getString("path"),
                    title         = o.getString("title"),
                    artist        = o.optString("artist"),
                    album         = o.optString("album"),
                    genre         = o.optString("genre"),
                    year          = o.optString("year"),
                    duration      = o.optDouble("duration", 0.0).toFloat(),
                    albumId       = o.optLong("albumId", 0L),
                    tempo         = o.optDouble("tempo", 0.0).toFloat(),
                    energy        = o.optDouble("energy", 0.0).toFloat(),
                    mood          = o.optString("mood"),
                    genreResolved = o.optString("genreResolved"),
                    analyzed      = o.optBoolean("analyzed", false),
                )
            }.filter { File(it.path).exists() }   // scarta file rimossi
        } catch (_: Exception) { emptyList() }
    }

    fun clear(context: Context) {
        try { File(context.filesDir, CACHE_FILE).delete() } catch (_: Exception) {}
    }

    // ── Preferenze semplici ────────────────────────────────────────────────────

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveScanFolder(context: Context, folder: String?) =
        prefs(context).edit().putString(KEY_FOLDER, folder).apply()

    fun loadScanFolder(context: Context): String? =
        prefs(context).getString(KEY_FOLDER, null)
}
