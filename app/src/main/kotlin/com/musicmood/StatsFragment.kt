package com.musicmood

import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.google.android.material.progressindicator.LinearProgressIndicator

class StatsFragment : Fragment() {

    private val vm: SongViewModel by activityViewModels()
    private lateinit var container: LinearLayout
    private lateinit var tvEmpty: TextView

    override fun onCreateView(inflater: LayoutInflater, c: ViewGroup?, s: Bundle?): View =
        inflater.inflate(R.layout.fragment_stats, c, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        container = view.findViewById(R.id.statsContainer)
        tvEmpty   = view.findViewById(R.id.tvStatsEmpty)
        vm.songs.observe(viewLifecycleOwner) { refresh() }
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun refresh() {
        container.removeAllViews()
        val all = vm.songs.value ?: emptyList()
        val analyzed = all.filter { it.analyzed }
        if (analyzed.isEmpty()) { tvEmpty.visibility = View.VISIBLE; return }
        tvEmpty.visibility = View.GONE

        // Pulsante per ripetere l'analisi con l'algoritmo aggiornato
        addRescanButton()

        addHeader("📊 Libreria")
        addRow("Brani totali", "${all.size}")
        addRow("Analizzati", "${analyzed.size}")
        addRow("Durata totale", formatTotal(analyzed.sumOf { it.duration.toDouble() }))

        val moodCounts = analyzed.groupBy { it.mood }.mapValues { it.value.size }
            .toList().sortedByDescending { it.second }
        if (moodCounts.isNotEmpty()) {
            addHeader("🎭 Mood")
            moodCounts.forEach { (mood, count) ->
                val pct = count * 100 / analyzed.size
                val color = SongAdapter.MOOD_COLORS[mood] ?: (0xFF1DB954L).toInt()
                addBar(mood, count, pct, color)
            }
        }

        val genreCounts = analyzed.filter { it.genreResolved.isNotBlank() }
            .groupBy { it.genreResolved }.mapValues { it.value.size }
            .toList().sortedByDescending { it.second }
        if (genreCounts.isNotEmpty()) {
            addHeader("🎵 Generi")
            val max = genreCounts.first().second
            genreCounts.forEach { (g, c) ->
                addBar(g, c, c * 100 / max, (0xFF6C3FC5L).toInt())
            }
        }

        val tempos = analyzed.map { it.tempo }.filter { it > 0 }
        if (tempos.isNotEmpty()) {
            addHeader("🥁 BPM")
            addRow("Media",  "%.0f BPM".format(tempos.average()))
            addRow("Minimo", "%.0f BPM".format(tempos.min()))
            addRow("Massimo","%.0f BPM".format(tempos.max()))
        }

        val years = analyzed.filter { it.year.length == 4 }
            .groupBy { it.year }.mapValues { it.value.size }
            .toList().sortedByDescending { it.first.toIntOrNull() ?: 0 }
        if (years.isNotEmpty()) {
            addHeader("📅 Anni")
            val maxY = years.maxOf { it.second }
            years.forEach { (y, c) ->
                addBar(y, c, c * 100 / maxY, (0xFF10B981L).toInt())
            }
        }

        addHeader("⚡ Top 5 Energia")
        analyzed.sortedByDescending { it.energy }.take(5).forEachIndexed { i, s ->
            addRow("${i+1}. ${s.title.take(28)}", "${s.mood}  ·  ${s.tempo.toInt()} BPM")
        }
    }

    private fun addRescanButton() {
        val btn = com.google.android.material.button.MaterialButton(requireContext()).apply {
            text = "🔄  Rianalizza libreria"
            textSize = 12f
            val lp = LinearLayout.LayoutParams(MATCH, WRAP)
            lp.setMargins(0, dp(8), 0, dp(4))
            layoutParams = lp
            setBackgroundColor((0x00000000L).toInt())
            setTextColor((0xFF000000L).toInt())
            strokeWidth = 0

            setOnClickListener {
                SongCache.clear(requireContext())
                vm.setScanState(ScanState.IDLE)
                vm.setSongs(emptyList())
                (activity as? MainActivity)?.goToLibrary()
            }
        }
        container.addView(btn)
    }

    private fun formatTotal(seconds: Double): String {
        val h = (seconds / 3600).toInt()
        val m = ((seconds % 3600) / 60).toInt()
        return if (h > 0) "${h}h ${m}min" else "${m}min"
    }

    private fun addHeader(text: String) {
        container.addView(TextView(requireContext()).apply {
            this.text = text
            textSize = 12f
            setTextColor((0xFFB3B3B3L).toInt())
            setTypeface(null, android.graphics.Typeface.BOLD)
            val lp = LinearLayout.LayoutParams(MATCH, WRAP)
            lp.setMargins(0, dp(24), 0, dp(8))
            layoutParams = lp
            letterSpacing = 0.10f
        })
    }

    private fun addRow(label: String, value: String) {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setBackgroundColor((0x00000000L).toInt())
            val lp = LinearLayout.LayoutParams(MATCH, WRAP)
            lp.setMargins(0, dp(2), 0, 0)
            layoutParams = lp
        }
        row.addView(TextView(requireContext()).apply {
            this.text = label; textSize = 13f
            setTextColor((0xFFF1F0FFL).toInt())
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        })
        row.addView(TextView(requireContext()).apply {
            this.text = value; textSize = 13f
            setTextColor((0xFF9CA3AFL).toInt())
        })
        container.addView(row)
    }

    private fun addBar(label: String, count: Int, pct: Int, color: Int) {
        val wrap = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setBackgroundColor((0x00000000L).toInt())
            val lp = LinearLayout.LayoutParams(MATCH, WRAP)
            lp.setMargins(0, dp(2), 0, 0)
            layoutParams = lp
        }
        val row = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(TextView(requireContext()).apply {
            this.text = label; textSize = 12f
            setTextColor(color)
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        })
        row.addView(TextView(requireContext()).apply {
            this.text = "$count"; textSize = 12f
            setTextColor((0xFF9CA3AFL).toInt())
        })
        val bar = LinearProgressIndicator(requireContext()).apply {
            max = 100; progress = pct
            setIndicatorColor(color)
            trackColor = (0xFF333333L).toInt()
            val lp2 = LinearLayout.LayoutParams(MATCH, dp(4))
            lp2.setMargins(0, dp(4), 0, 0)
            layoutParams = lp2
        }
        wrap.addView(row); wrap.addView(bar)
        container.addView(wrap)
    }

    private val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
    private val WRAP  = LinearLayout.LayoutParams.WRAP_CONTENT
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
