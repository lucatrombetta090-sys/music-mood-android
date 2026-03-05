package com.musicmood

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.PowerManager
import android.provider.MediaStore
import androidx.lifecycle.*
import com.chaquo.python.Python
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject

enum class ScanState { IDLE, SCANNING, ANALYZING, DONE, ERROR }

data class Filters(
    val mood: String = "Tutti",
    val genre: String = "Tutti",
    val year: String = "Tutti",
    val searchQuery: String = ""
)

class SongViewModel : ViewModel() {

    private val _songs        = MutableLiveData<List<Song>>(emptyList())
    private val _scanState    = MutableLiveData(ScanState.IDLE)
    private val _scanProgress = MutableLiveData(0 to 0)
    private val _scanError    = MutableLiveData("")
    private val _filters      = MutableLiveData(Filters())
    private val _currentSong  = MutableLiveData<Song?>(null)
    private val _isPlaying    = MutableLiveData(false)
    private val _scanFolder   = MutableLiveData<String?>(null)

    val songs:        LiveData<List<Song>>     = _songs
    val scanState:    LiveData<ScanState>      = _scanState
    val scanProgress: LiveData<Pair<Int,Int>>  = _scanProgress
    val scanError:    LiveData<String>         = _scanError
    val filters:      LiveData<Filters>        = _filters
    val currentSong:  LiveData<Song?>          = _currentSong
    val isPlaying:    LiveData<Boolean>        = _isPlaying
    val scanFolder:   LiveData<String?>        = _scanFolder

    var playlist: List<Song> = emptyList()
    var playlistIndex: Int = 0

    private var analysisJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    fun setSongs(list: List<Song>)          { _songs.postValue(list) }
    fun setScanState(s: ScanState)          { _scanState.postValue(s) }
    fun setScanProgress(cur: Int, tot: Int) { _scanProgress.postValue(cur to tot) }
    fun setScanError(msg: String)           { _scanError.postValue(msg) }
    fun setCurrentSong(s: Song?)            { _currentSong.postValue(s) }
    fun setIsPlaying(v: Boolean)            { _isPlaying.postValue(v) }
    fun setScanFolder(path: String?)        { _scanFolder.postValue(path) }

    fun setFilter(mood: String? = null, genre: String? = null,
                  year: String? = null, search: String? = null) {
        val cur = _filters.value ?: Filters()
        _filters.postValue(cur.copy(
            mood        = mood   ?: cur.mood,
            genre       = genre  ?: cur.genre,
            year        = year   ?: cur.year,
            searchQuery = search ?: cur.searchQuery
        ))
    }

    fun updateSong(updated: Song) {
        val list = _songs.value?.toMutableList() ?: return
        val idx  = list.indexOfFirst { it.path == updated.path }
        if (idx >= 0) { list[idx] = updated; _songs.postValue(list) }
    }

    fun getAnalyzedSongs() = _songs.value?.filter { it.analyzed } ?: emptyList()

    fun getFilteredSongs(): List<Song> {
        val f = _filters.value ?: Filters()
        return _songs.value?.filter { s ->
            (f.mood  == "Tutti" || s.mood == f.mood) &&
            (f.genre == "Tutti" || s.genreResolved == f.genre) &&
            (f.year  == "Tutti" || s.year == f.year) &&
            (f.searchQuery.isBlank() ||
                s.title.contains(f.searchQuery, ignoreCase = true) ||
                s.artist.contains(f.searchQuery, ignoreCase = true))
        } ?: emptyList()
    }

    fun availableGenres() = listOf("Tutti") + (_songs.value
        ?.filter { it.analyzed && it.genreResolved.isNotBlank() }
        ?.map { it.genreResolved }?.distinct()?.sorted() ?: emptyList())

    fun availableYears() = listOf("Tutti") + (_songs.value
        ?.filter { it.year.length == 4 }
        ?.map { it.year }?.distinct()?.sortedDescending() ?: emptyList())

    // ── Caricamento cache all'avvio ────────────────────────────────────────────

    fun loadCache(context: Context) {
        if (_songs.value?.isNotEmpty() == true) return   // già caricato
        viewModelScope.launch(Dispatchers.IO) {
            val cached = SongCache.load(context)
            val folder = SongCache.loadScanFolder(context)
            if (cached.isNotEmpty()) {
                _songs.postValue(cached)
                _scanFolder.postValue(folder)
                _scanState.postValue(ScanState.DONE)
            }
        }
    }

    // ── Album art ─────────────────────────────────────────────────────────────

    fun getAlbumArt(context: Context, albumId: Long): Bitmap? {
        if (albumId <= 0L) return null
        return try {
            val uri = ContentUris.withAppendedId(
                Uri.parse("content://media/external/audio/albumart"), albumId)
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it)
            }
        } catch (_: Exception) { null }
    }

    // ── Analisi nel viewModelScope ─────────────────────────────────────────────

    fun startAnalysis(context: Context, folderFilter: String?) {
        if (_scanState.value == ScanState.SCANNING ||
            _scanState.value == ScanState.ANALYZING) return

        analysisJob?.cancel()
        _scanState.postValue(ScanState.SCANNING)
        _scanError.postValue("")

        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, "MusicMood::Analysis"
        ).apply { acquire(60 * 60 * 1000L) }

        analysisJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                // ── 1. MediaStore ──────────────────────────────────────────────
                val songs = mutableListOf<Song>()
                val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                val projection = arrayOf(
                    MediaStore.Audio.Media.DATA,
                    MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.ARTIST,
                    MediaStore.Audio.Media.ALBUM,
                    MediaStore.Audio.Media.ALBUM_ID,
                    MediaStore.Audio.Media.DURATION,
                    MediaStore.Audio.Media.YEAR,
                    MediaStore.Audio.Media.MIME_TYPE,
                )
                var sel = "${MediaStore.Audio.Media.IS_MUSIC} != 0 " +
                        "AND ${MediaStore.Audio.Media.DURATION} > 30000"
                if (!folderFilter.isNullOrBlank())
                    sel += " AND ${MediaStore.Audio.Media.DATA} LIKE '${folderFilter.replace("'","''")}%'"

                context.contentResolver.query(
                    uri, projection, sel, null, "${MediaStore.Audio.Media.TITLE} ASC"
                )?.use { c ->
                    val pathCol    = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                    val titleCol   = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                    val artistCol  = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                    val albumCol   = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                    val albumIdCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                    val durCol     = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                    val yearCol    = c.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
                    val mimeCol    = c.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)

                    while (c.moveToNext()) {
                        val mime = c.getString(mimeCol) ?: ""
                        if (!mime.startsWith("audio/")) continue
                        val path = c.getString(pathCol) ?: continue
                        val yr   = c.getInt(yearCol).let { if (it > 1900) it.toString() else "" }
                        songs.add(Song(
                            path     = path,
                            title    = c.getString(titleCol)?.ifBlank { null }
                                        ?: path.substringAfterLast("/").substringBeforeLast("."),
                            artist   = c.getString(artistCol)?.let {
                                if (it == "<unknown>") "" else it } ?: "",
                            album    = c.getString(albumCol) ?: "",
                            genre    = "",
                            year     = yr,
                            duration = c.getLong(durCol) / 1000f,
                            albumId  = c.getLong(albumIdCol),
                        ))
                    }
                }

                if (songs.isEmpty()) {
                    _scanState.postValue(ScanState.ERROR)
                    _scanError.postValue(
                        "Nessun file audio trovato. Controlla i permessi e il percorso selezionato.")
                    releaseWakeLock(); return@launch
                }

                _songs.postValue(songs)
                _scanState.postValue(ScanState.ANALYZING)
                setScanProgress(0, songs.size)

                // ── 2. DSP parallela (4 slot) ──────────────────────────────────
                val py  = Python.getInstance()
                val mod = py.getModule("music_analyzer")
                val sem = Semaphore(4)
                var done = 0

                coroutineScope {
                    songs.map { song ->
                        async(Dispatchers.IO) {
                            sem.withPermit {
                                try {
                                    val tag  = JSONObject(
                                        mod.callAttr("read_tags", song.path).toString())
                                    val genreTag = tag.optString("genre", "")

                                    val decoded = AudioDecoder.decode(song.path)
                                    if (decoded != null) {
                                        val (pcm, sr) = decoded
                                        val res = JSONObject(
                                            mod.callAttr("analyze_pcm", pcm, sr).toString())
                                        song.tempo         = res.optDouble("tempo", 120.0).toFloat()
                                        song.energy        = res.optDouble("energy", 0.0).toFloat()
                                        song.mood          = res.optString("mood", "Positivo")
                                        song.genreResolved = genreTag.ifBlank {
                                            res.optString("genre_hint", "Pop") }
                                    } else {
                                        song.mood          = "Positivo"
                                        song.genreResolved = genreTag.ifBlank { "Pop" }
                                    }
                                    song.analyzed = true
                                } catch (_: Exception) {
                                    song.analyzed = true
                                    song.mood          = "Positivo"
                                    song.genreResolved = "Pop"
                                }
                                withContext(Dispatchers.Main) {
                                    updateSong(song)
                                    done++
                                    setScanProgress(done, songs.size)
                                    // Salva ogni 50 brani per non perdere progressi
                                    if (done % 50 == 0) {
                                        val snapshot = _songs.value ?: emptyList()
                                        SongCache.save(context, snapshot)
                                    }
                                }
                            }
                        }
                    }.awaitAll()
                }

                // Salvataggio finale completo
                val finalList = _songs.value ?: emptyList()
                SongCache.save(context, finalList)
                SongCache.saveScanFolder(context, folderFilter)

                _scanState.postValue(ScanState.DONE)

            } catch (e: CancellationException) {
                _scanState.postValue(ScanState.IDLE)
            } catch (e: Exception) {
                _scanState.postValue(ScanState.ERROR)
                _scanError.postValue("Errore: ${e.message?.take(120)}")
            } finally {
                releaseWakeLock()
            }
        }
    }

    fun cancelAnalysis() {
        analysisJob?.cancel()
        releaseWakeLock()
    }

    private fun releaseWakeLock() {
        try { wakeLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        wakeLock = null
    }

    override fun onCleared() {
        super.onCleared()
        cancelAnalysis()
    }
}
