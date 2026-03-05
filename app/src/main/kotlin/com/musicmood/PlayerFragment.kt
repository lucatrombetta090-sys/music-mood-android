package com.musicmood

import android.content.ContentUris
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.*
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider

class PlayerFragment : Fragment() {

    private val vm: SongViewModel by activityViewModels()

    private lateinit var tvTitle: TextView
    private lateinit var tvArtist: TextView
    private lateinit var tvMood: TextView
    private lateinit var tvGenre: TextView
    private lateinit var tvTempo: TextView
    private lateinit var tvTime: TextView
    private lateinit var tvDuration: TextView
    private lateinit var slider: Slider
    private lateinit var btnPlay: MaterialButton
    private lateinit var btnPrev: MaterialButton
    private lateinit var btnNext: MaterialButton
    private lateinit var btnLoop: MaterialButton
    private lateinit var btnShuffle: MaterialButton
    private lateinit var volSlider: Slider
    private lateinit var tvNoSong: TextView
    private lateinit var playerContent: View
    private lateinit var ivArtwork: ImageView
    private lateinit var tvArtworkLetter: TextView
    private lateinit var artworkBg: View

    private var player: MediaPlayer? = null
    private var isPreparing = false     // FIX: blocca doppio start
    private var isLoop = false
    private var isShuffle = false
    private val handler = Handler(Looper.getMainLooper())
    private var isSeeking = false

    private val updateProgress = object : Runnable {
        override fun run() {
            if (!isSeeking) {
                try {
                    val p = player ?: return
                    if (p.isPlaying) {
                        val pos = p.currentPosition
                        if (slider.valueTo > 1f)
                            slider.value = pos.toFloat().coerceIn(0f, slider.valueTo)
                        tvTime.text = formatMs(pos)
                    }
                } catch (_: Exception) {}
            }
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_player, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        tvTitle         = view.findViewById(R.id.tvPlayerTitle)
        tvArtist        = view.findViewById(R.id.tvPlayerArtist)
        tvMood          = view.findViewById(R.id.tvPlayerMood)
        tvGenre         = view.findViewById(R.id.tvPlayerGenre)
        tvTempo         = view.findViewById(R.id.tvPlayerTempo)
        tvTime          = view.findViewById(R.id.tvTime)
        tvDuration      = view.findViewById(R.id.tvDuration)
        slider          = view.findViewById(R.id.seekSlider)
        btnPlay         = view.findViewById(R.id.btnPlay)
        btnPrev         = view.findViewById(R.id.btnPrev)
        btnNext         = view.findViewById(R.id.btnNext)
        btnLoop         = view.findViewById(R.id.btnLoop)
        btnShuffle      = view.findViewById(R.id.btnShuffle)
        volSlider       = view.findViewById(R.id.volSlider)
        tvNoSong        = view.findViewById(R.id.tvNoSong)
        playerContent   = view.findViewById(R.id.playerContent)
        ivArtwork       = view.findViewById(R.id.ivArtwork)
        tvArtworkLetter = view.findViewById(R.id.tvArtworkLetter)
        artworkBg       = view.findViewById(R.id.artworkBg)

        btnPlay.setOnClickListener { togglePlay() }
        btnPrev.setOnClickListener { playPrev() }
        btnNext.setOnClickListener { playNext() }

        btnLoop.setOnClickListener {
            isLoop = !isLoop
            btnLoop.alpha = if (isLoop) 1f else 0.35f
            player?.isLooping = isLoop
        }
        btnShuffle.setOnClickListener {
            isShuffle = !isShuffle
            btnShuffle.alpha = if (isShuffle) 1f else 0.35f
        }

        slider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(s: Slider) { isSeeking = true }
            override fun onStopTrackingTouch(s: Slider) {
                try { player?.seekTo(s.value.toInt()) } catch (_: Exception) {}
                tvTime.text = formatMs(s.value.toInt())
                isSeeking = false
            }
        })

        volSlider.value = 80f
        volSlider.addOnChangeListener { _, value, _ ->
            val v = value / 100f
            try { player?.setVolume(v, v) } catch (_: Exception) {}
        }

        // Osserva solo una volta — non riavviare se già in riproduzione
        vm.currentSong.observe(viewLifecycleOwner) { song ->
            if (song != null) {
                playerContent.visibility = View.VISIBLE
                tvNoSong.visibility = View.GONE
                updateUI(song)
                // Riavvia solo se è un brano diverso
                val currentPath = try { player?.let { if (it.isPlaying || isPreparing) "playing" else "" } } catch (_: Exception) { null }
                if (currentPath != "playing") {
                    playSong(song)
                }
            } else {
                playerContent.visibility = View.GONE
                tvNoSong.visibility = View.VISIBLE
                stopPlayer()
            }
        }

        // Riprende aggiornamento progress se torna visibile mentre in play
        vm.isPlaying.observe(viewLifecycleOwner) { playing ->
            if (playing) {
                btnPlay.text = "⏸"
                handler.post(updateProgress)
            } else {
                btnPlay.text = "▶"
            }
        }
    }

    private fun updateUI(song: Song) {
        tvTitle.text  = song.title.ifBlank { "Sconosciuto" }
        tvArtist.text = buildString {
            append(song.artist.ifBlank { "Artista sconosciuto" })
            if (song.year.isNotBlank()) append("  ·  ${song.year}")
        }

        // Copertina album
        val bmp = if (song.albumId > 0L) {
            try {
                val artUri = ContentUris.withAppendedId(
                    Uri.parse("content://media/external/audio/albumart"), song.albumId)
                requireContext().contentResolver.openInputStream(artUri)?.use {
                    BitmapFactory.decodeStream(it)
                }
            } catch (_: Exception) { null }
        } else null

        if (bmp != null) {
            ivArtwork.setImageBitmap(bmp)
            ivArtwork.visibility    = View.VISIBLE
            tvArtworkLetter.visibility = View.GONE
        } else {
            ivArtwork.visibility    = View.INVISIBLE
            tvArtworkLetter.visibility = View.VISIBLE
            tvArtworkLetter.text = song.title.firstOrNull()?.uppercaseChar()?.toString() ?: "♪"
        }

        // Colore mood
        if (song.analyzed && song.mood.isNotBlank()) {
            tvMood.text  = song.mood
            tvGenre.text = song.genreResolved.ifBlank { "—" }
            tvTempo.text = "${song.tempo.toInt()} BPM"
            tvMood.visibility = View.VISIBLE

            val color    = SongAdapter.MOOD_COLORS[song.mood]    ?: (0xFF1DB954L).toInt()
            val colorDim = SongAdapter.MOOD_COLORS_DIM[song.mood] ?: (0x331DB954L).toInt()
            tvMood.setTextColor((0xFF000000L).toInt())

            if (bmp == null) {
                artworkBg.setBackgroundColor((0xFF282828L).toInt())
            }
        } else {
            tvMood.visibility = View.GONE
            tvGenre.text = "—"
            tvTempo.text = "—"
            if (bmp == null) artworkBg.setBackgroundColor((0xFF1A1A2EL).toInt())
        }
    }

    internal fun playSong(song: Song) {
        stopPlayer()
        isPreparing = true
        btnPlay.text = "▶"

        try {
            player = MediaPlayer().apply {
                setDataSource(song.path)
                val vol = volSlider.value / 100f
                setVolume(vol, vol)
                isLooping = isLoop

                setOnPreparedListener {
                    isPreparing = false
                    val dur = duration.toFloat().coerceAtLeast(1f)
                    slider.valueTo = dur
                    slider.value   = 0f
                    tvDuration.text = formatMs(duration)
                    // start() chiamato solo qui — mai in togglePlay mentre isPreparing
                    start()
                    btnPlay.text = "⏸"
                    vm.setIsPlaying(true)
                    handler.post(updateProgress)
                }
                setOnErrorListener { _, _, _ ->
                    isPreparing = false
                    btnPlay.text = "▶"
                    true
                }
                setOnCompletionListener {
                    vm.setIsPlaying(false)
                    if (!isLoop) { if (isShuffle) playRandom() else playNext() }
                }
                prepareAsync()
            }
        } catch (_: Exception) {
            isPreparing = false
            btnPlay.text = "▶"
        }
    }

    internal fun togglePlay() {
        // FIX: ignora il tap se stiamo ancora caricando
        if (isPreparing) return
        val p = player ?: return
        if (p.isPlaying) {
            p.pause()
            vm.setIsPlaying(false)
        } else {
            p.start()
            vm.setIsPlaying(true)
        }
    }

    private fun playNext() {
        val pl = vm.playlist; if (pl.isEmpty()) return
        vm.playlistIndex = (vm.playlistIndex + 1) % pl.size
        vm.setCurrentSong(pl[vm.playlistIndex])
        playSong(pl[vm.playlistIndex])
    }
    private fun playPrev() {
        val pl = vm.playlist; if (pl.isEmpty()) return
        vm.playlistIndex = (vm.playlistIndex - 1 + pl.size) % pl.size
        vm.setCurrentSong(pl[vm.playlistIndex])
        playSong(pl[vm.playlistIndex])
    }
    private fun playRandom() {
        val pl = vm.playlist; if (pl.isEmpty()) return
        vm.playlistIndex = (0 until pl.size).random()
        vm.setCurrentSong(pl[vm.playlistIndex])
        playSong(pl[vm.playlistIndex])
    }

    private fun stopPlayer() {
        handler.removeCallbacks(updateProgress)
        isPreparing = false
        try { player?.stop() } catch (_: Exception) {}
        try { player?.release() } catch (_: Exception) {}
        player = null
        vm.setIsPlaying(false)
        try {
            slider.valueTo = 1f
            slider.value   = 0f
            tvTime.text    = "0:00"
        } catch (_: Exception) {}
    }

    private fun formatMs(ms: Int): String {
        val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacks(updateProgress)
        // Non fermiamo il player — solo rimuoviamo callbacks
        // così la musica continua anche navigando tra fragment
    }

    // Called by MainActivity mini player
    fun getProgressPercent(): Int {
        val p = player ?: return 0
        return try {
            val dur = p.duration
            if (dur <= 0) 0
            else (p.currentPosition * 100 / dur)
        } catch (_: Exception) { 0 }
    }

    fun togglePlayFromMini() {
        if (isPreparing) return
        try {
            val p = player
            if (p == null) {
                // No player yet: play current song from ViewModel
                vm.currentSong.value?.let { playSong(it) }
            } else {
                togglePlay()
            }
        } catch (_: Exception) {}
    }

    fun nextFromMini() {
        val pl = vm.playlist
        if (pl.isEmpty()) return
        vm.playlistIndex = (vm.playlistIndex + 1) % pl.size
        val next = pl[vm.playlistIndex]
        vm.setCurrentSong(next)
        playSong(next)
    }

}
