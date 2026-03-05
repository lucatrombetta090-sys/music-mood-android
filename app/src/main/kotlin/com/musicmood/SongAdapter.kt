package com.musicmood

import android.content.ContentUris
import android.graphics.BitmapFactory
import android.net.Uri
import android.view.*
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

class SongAdapter(
    private val onClick: (Song) -> Unit,
    private val onLongClick: (Song) -> Unit
) : ListAdapter<Song, SongAdapter.VH>(DIFF) {

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<Song>() {
            override fun areItemsTheSame(a: Song, b: Song) = a.path == b.path
            override fun areContentsTheSame(a: Song, b: Song) = a == b
        }
        val MOOD_COLORS = mapOf(
            "Energico"    to (0xFFF59E0BL).toInt(),
            "Positivo"    to (0xFF1DB954L).toInt(),
            "Aggressivo"  to (0xFFEF4444L).toInt(),
            "Malinconico" to (0xFF7C3AEDL).toInt(),
        )
        val MOOD_COLORS_DIM = mapOf(
            "Energico"    to (0x33F59E0BL).toInt(),
            "Positivo"    to (0x331DB954L).toInt(),
            "Aggressivo"  to (0x33EF4444L).toInt(),
            "Malinconico" to (0x337C3AEDL).toInt(),
        )
    }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val root: View         = v.findViewById(R.id.cardSong)
        val ivArt: ImageView   = v.findViewById(R.id.ivAlbumArt)
        val tvLetter: TextView = v.findViewById(R.id.tvArtLetter)
        val tvTitle: TextView  = v.findViewById(R.id.tvTitle)
        val tvArtist: TextView = v.findViewById(R.id.tvArtist)
        val tvMeta: TextView   = v.findViewById(R.id.tvMeta)
        val tvMood: TextView   = v.findViewById(R.id.tvMood)
        val tvDuration: TextView = v.findViewById(R.id.tvDuration)

        fun bind(song: Song) {
            // Album art
            val bmp = if (song.albumId > 0L) {
                try {
                    val uri = ContentUris.withAppendedId(
                        Uri.parse("content://media/external/audio/albumart"), song.albumId)
                    itemView.context.contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it)
                    }
                } catch (_: Exception) { null }
            } else null

            if (bmp != null) {
                ivArt.setImageBitmap(bmp)
                ivArt.visibility    = View.VISIBLE
                tvLetter.visibility = View.GONE
            } else {
                ivArt.visibility    = View.INVISIBLE
                tvLetter.visibility = View.VISIBLE
                tvLetter.text = song.title.firstOrNull()?.uppercaseChar()?.toString() ?: "♪"
            }

            tvTitle.text = song.title.ifBlank { "Sconosciuto" }
            tvArtist.text = buildString {
                append(song.artist.ifBlank { "Artista sconosciuto" })
                if (song.year.isNotBlank()) append("  ·  ${song.year}")
            }
            val dur = song.duration.toInt()
            tvDuration.text = "%d:%02d".format(dur / 60, dur % 60)

            if (song.analyzed && song.mood.isNotBlank()) {
                tvMeta.text = song.genreResolved.ifBlank { "" } +
                    if (song.tempo > 0) "  ·  ${song.tempo.toInt()} BPM" else ""
                val color = MOOD_COLORS[song.mood] ?: (0xFF1DB954L).toInt()
                tvMood.text = song.mood.uppercase()
                tvMood.setTextColor(color)
                tvMood.visibility = View.VISIBLE
                // Background del titolo: sottile bordo sinistro colorato tramite padding
                root.setBackgroundColor(0x00000000)
            } else {
                tvMeta.text = if (song.analyzed) "" else "..."
                tvMood.visibility = View.GONE
                root.setBackgroundColor(0x00000000)
            }

            root.setOnClickListener { onClick(song) }
            root.setOnLongClickListener { onLongClick(song); true }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_song, parent, false))

    override fun onBindViewHolder(h: VH, pos: Int) = h.bind(getItem(pos))
}
