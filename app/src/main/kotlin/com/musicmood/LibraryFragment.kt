package com.musicmood

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText

class LibraryFragment : Fragment() {

    private val vm: SongViewModel by activityViewModels()
    private lateinit var adapter: SongAdapter
    private lateinit var rv: RecyclerView
    private lateinit var btnScan: MaterialButton
    private lateinit var btnFilter: MaterialButton
    private lateinit var progressBar: LinearProgressIndicator
    private lateinit var tvStatus: TextView
    private lateinit var tvCount: TextView
    private lateinit var tvEmpty: TextView
    private lateinit var etSearch: TextInputEditText
    private lateinit var chipMoodRow: ChipGroup

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.any { it }) showFolderDialog()
        else Snackbar.make(requireView(),
            "Permesso negato. Vai in Impostazioni → App → Autorizzazioni.",
            Snackbar.LENGTH_LONG).show()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_library, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        rv          = view.findViewById(R.id.rvSongs)
        btnScan     = view.findViewById(R.id.btnScan)
        btnFilter   = view.findViewById(R.id.btnFilter)
        progressBar = view.findViewById(R.id.progressScan)
        tvStatus    = view.findViewById(R.id.tvStatus)
        tvCount     = view.findViewById(R.id.tvCount)
        tvEmpty     = view.findViewById(R.id.tvEmpty)
        etSearch    = view.findViewById(R.id.etSearch)
        chipMoodRow = view.findViewById(R.id.chipMoodRow)

        adapter = SongAdapter(
            onClick = { song ->
                vm.playlist = vm.getFilteredSongs()
                vm.playlistIndex = vm.playlist.indexOfFirst { it.path == song.path }.coerceAtLeast(0)
                vm.setCurrentSong(song)
                (activity as? MainActivity)?.goToPlayer()
            },
            onLongClick = { }
        )
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = adapter

        // Mood chips in italiano
        listOf("Tutti", "Energico", "Positivo", "Aggressivo", "Malinconico").forEachIndexed { i, mood ->
            chipMoodRow.addView(Chip(requireContext()).apply {
                text = mood; isCheckable = true; isChecked = i == 0
                chipBackgroundColor = resources.getColorStateList(R.color.chip_bg_selector, null)
                setTextColor(resources.getColorStateList(R.color.chip_text_selector, null))
                setOnClickListener { vm.setFilter(mood = mood) }
            })
        }

        btnScan.setOnClickListener { checkPermsAndScan() }
        btnFilter.setOnClickListener { showFilterSheet() }
        etSearch.doAfterTextChanged { vm.setFilter(search = it?.toString() ?: "") }

        vm.songs.observe(viewLifecycleOwner) { updateList() }
        vm.filters.observe(viewLifecycleOwner) { updateList() }

        vm.scanState.observe(viewLifecycleOwner) { state ->
            btnScan.isEnabled = state != ScanState.SCANNING && state != ScanState.ANALYZING
            progressBar.visibility = if (
                state == ScanState.SCANNING || state == ScanState.ANALYZING
            ) View.VISIBLE else View.GONE
            when (state) {
                ScanState.IDLE -> tvStatus.text = "Premi SCANSIONA per iniziare"
                ScanState.DONE -> tvStatus.text = "✓ ${vm.getAnalyzedSongs().size} brani analizzati"
                else -> {}
            }
        }
        vm.scanProgress.observe(viewLifecycleOwner) { (cur, tot) ->
            if (tot > 0) {
                progressBar.max = tot; progressBar.progress = cur
                tvStatus.text = when (vm.scanState.value) {
                    ScanState.ANALYZING -> "Analisi mood $cur/$tot…"
                    else                -> "Lettura file $cur/$tot…"
                }
            }
        }
        vm.scanError.observe(viewLifecycleOwner) { err ->
            if (err.isNotBlank()) Snackbar.make(requireView(), err, Snackbar.LENGTH_LONG).show()
        }
        vm.scanFolder.observe(viewLifecycleOwner) { folder ->
            val label = folder?.substringAfterLast("/") ?: "Tutto il dispositivo"
            btnScan.text = "📂 $label"
        }
    }

    private fun updateList() {
        val filtered = vm.getFilteredSongs()
        adapter.submitList(filtered.toList())
        val total = vm.songs.value?.size ?: 0
        tvCount.text = "${filtered.size} di $total brani"
        tvEmpty.visibility = if (filtered.isEmpty() && total > 0) View.VISIBLE else View.GONE
    }

    // ── Selezione cartella con dialog NATIVO (nessun tema custom) ─────────────

    private fun checkPermsAndScan() {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            arrayOf(Manifest.permission.READ_MEDIA_AUDIO)
        else
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

        val ok = perms.all {
            ContextCompat.checkSelfPermission(requireContext(), it) == PackageManager.PERMISSION_GRANTED
        }
        if (ok) showFolderDialog() else permLauncher.launch(perms)
    }

    private fun showFolderDialog() {
        val base = Environment.getExternalStorageDirectory().absolutePath
        val cur  = vm.scanFolder.value

        // Due liste parallele: evita problemi di inferenza tipo su Pair
        val optLabels = mutableListOf(
            "🌐  Tutto il dispositivo",
            "🎵  Music",
            "⬇️  Download",
            "💬  WhatsApp Audio",
            "✈️  Telegram Audio",
            "📁  Percorso personalizzato…"
        )
        val optPaths = mutableListOf<String?>(
            null,
            "$base/Music",
            "$base/Download",
            "$base/WhatsApp/Media/WhatsApp Audio",
            "$base/Telegram/Telegram Audio",
            "__custom__"
        )
        if (cur != null && !optPaths.contains(cur)) {
            optLabels.add(0, "📂  $cur")
            optPaths.add(0, cur)
        }

        val labels    = optLabels.toTypedArray()
        val checkedIdx = optPaths.indexOf(cur).coerceAtLeast(0)


        // Usa AlertDialog base senza tema custom: evita item invisibili
        AlertDialog.Builder(requireContext())
            .setTitle("Dove cercare la musica?")
            .setSingleChoiceItems(labels, checkedIdx) { dialog, which ->
                val chosen = optPaths[which]
                if (chosen == "__custom__") {
                    dialog.dismiss()
                    showCustomPathDialog()
                } else {
                    vm.setScanFolder(chosen)
                    SongCache.saveScanFolder(requireContext(), chosen)
                    dialog.dismiss()
                    startAnalysis(chosen)
                }
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun showCustomPathDialog() {
        val input = EditText(requireContext()).apply {
            setText(vm.scanFolder.value?.takeIf { it != "__custom__" }
                ?: "/storage/emulated/0/Music")
            setPadding(56, 32, 56, 32)
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Percorso cartella")
            .setMessage("Inserisci il percorso assoluto della cartella con la tua musica.")
            .setView(input)
            .setPositiveButton("Scansiona") { _, _ ->
                val path = input.text.toString().trim().ifBlank { null }
                vm.setScanFolder(path)
                SongCache.saveScanFolder(requireContext(), path)
                startAnalysis(path)
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun startAnalysis(folder: String?) {
        val where = folder?.let { "in ${it.substringAfterLast("/")}" } ?: "su tutto il dispositivo"
        tvStatus.text = "Ricerca audio $where…"
        vm.startAnalysis(requireContext().applicationContext, folder)
    }

    // ── Filtri (bottom sheet) ─────────────────────────────────────────────────

    private fun showFilterSheet() {
        val ctx   = requireContext()
        val sheet = BottomSheetDialog(ctx, R.style.BottomSheetStyle)
        val sv    = layoutInflater.inflate(R.layout.sheet_filters, null)
        sheet.setContentView(sv)

        val chipGenre: ChipGroup  = sv.findViewById(R.id.chipGenre)
        val chipYear: ChipGroup   = sv.findViewById(R.id.chipYear)
        val btnApply: MaterialButton = sv.findViewById(R.id.btnApplyFilters)
        val btnReset: MaterialButton = sv.findViewById(R.id.btnResetFilters)
        val cur = vm.filters.value ?: return

        vm.availableGenres().forEach { g ->
            chipGenre.addView(Chip(ctx).apply {
                text = g; isCheckable = true; isChecked = g == cur.genre
                chipBackgroundColor = resources.getColorStateList(R.color.chip_bg_selector, null)
                setTextColor(resources.getColorStateList(R.color.chip_text_selector, null))
            })
        }
        vm.availableYears().forEach { y ->
            chipYear.addView(Chip(ctx).apply {
                text = y; isCheckable = true; isChecked = y == cur.year
                chipBackgroundColor = resources.getColorStateList(R.color.chip_bg_selector, null)
                setTextColor(resources.getColorStateList(R.color.chip_text_selector, null))
            })
        }

        btnApply.setOnClickListener {
            val g = (0 until chipGenre.childCount).map { chipGenre.getChildAt(it) as Chip }
                .firstOrNull { it.isChecked }?.text?.toString() ?: "Tutti"
            val y = (0 until chipYear.childCount).map { chipYear.getChildAt(it) as Chip }
                .firstOrNull { it.isChecked }?.text?.toString() ?: "Tutti"
            vm.setFilter(genre = g, year = y)
            val active = listOf(g, y).count { it != "Tutti" }
            btnFilter.text = if (active > 0) "Filtri ($active)" else "Filtri"
            sheet.dismiss()
        }
        btnReset.setOnClickListener {
            vm.setFilter(genre = "Tutti", year = "Tutti")
            btnFilter.text = "Filtri"
            sheet.dismiss()
        }
        sheet.show()
    }
}
