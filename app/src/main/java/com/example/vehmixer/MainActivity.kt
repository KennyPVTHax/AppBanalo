package com.example.vehmixer

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedWriter
import java.util.Random

class MainActivity : AppCompatActivity() {

    private val vehicleUris = mutableListOf<Uri>()
    private val phoneUris = mutableListOf<Uri>()
    private var lastOutputUri: Uri? = null

    private lateinit var tvVehicles: TextView
    private lateinit var tvPhones: TextView
    private lateinit var etSkip: EditText
    private lateinit var etReuseCount: EditText
    private lateinit var etReuseTimes: EditText
    private lateinit var progress: ProgressBar
    private lateinit var tvStatus: TextView
    private lateinit var btnShare: Button

    // pick MULTIPLE files at once for each side
    private val pickVehicles =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            vehicleUris.clear()
            vehicleUris.addAll(uris)
            refreshLabels()
        }

    private val pickPhones =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            phoneUris.clear()
            phoneUris.addAll(uris)
            refreshLabels()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    // ---------------- UI (built in code, no layout XML needed) ----------------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        textSize = 16f
        setPadding(0, dp(12), 0, dp(4))
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }

        root.addView(TextView(this).apply { text = "Vehicle/Phone Mixer"; textSize = 22f })

        root.addView(Button(this).apply {
            text = "1. Pick vehicle file(s) - multiple allowed"
            setOnClickListener { pickVehicles.launch(arrayOf("*/*")) }
        })
        tvVehicles = label("None selected").also { root.addView(it) }

        root.addView(Button(this).apply {
            text = "2. Pick phone file(s) - multiple allowed"
            setOnClickListener { pickPhones.launch(arrayOf("*/*")) }
        })
        tvPhones = label("None selected").also { root.addView(it) }

        root.addView(label("Skip rate % (vehicles with no phone)"))
        etSkip = EditText(this).apply { setText("12") }.also { root.addView(it) }

        root.addView(label("Reuse: how many phone numbers"))
        etReuseCount = EditText(this).apply { setText("3") }.also { root.addView(it) }

        root.addView(label("Reuse: allotted to how many vehicles each"))
        etReuseTimes = EditText(this).apply { setText("5") }.also { root.addView(it) }

        root.addView(Button(this).apply {
            text = "GENERATE CSV"
            setOnClickListener { onGenerate() }
        })

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = View.GONE
            setPadding(0, dp(12), 0, 0)
        }.also { root.addView(it) }

        tvStatus = label("").also { root.addView(it) }

        btnShare = Button(this).apply {
            text = "Share last output"
            visibility = View.GONE
            setOnClickListener { shareLast() }
        }.also { root.addView(it) }

        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)
    }

    private fun refreshLabels() {
        tvVehicles.text = summarize(vehicleUris)
        tvPhones.text = summarize(phoneUris)
        lastOutputUri = null
        btnShare.visibility = View.GONE
    }

    private fun summarize(uris: List<Uri>): String {
        if (uris.isEmpty()) return "None selected"
        val names = uris.take(5).map { queryName(it) }
        val more = if (uris.size > 5) ", ..." else ""
        return "${uris.size} file(s): ${names.joinToString(", ")}$more"
    }

    private fun queryName(uri: Uri): String {
        try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0 && c.moveToFirst()) return c.getString(i) ?: "file"
            }
        } catch (_: Exception) { }
        return uri.lastPathSegment ?: "file"
    }

    // ---------------- generation ----------------

    private fun onGenerate() {
        if (vehicleUris.isEmpty() || phoneUris.isEmpty()) {
            toast("Pick both a vehicle file and a phone file first")
            return
        }
        val skipRate = (etSkip.text.toString().toDoubleOrNull() ?: 12.0).coerceIn(0.0, 100.0)
        val reuseCount = etReuseCount.text.toString().toIntOrNull() ?: 3
        val reuseTimes = etReuseTimes.text.toString().toIntOrNull() ?: 5

        progress.visibility = View.VISIBLE
        tvStatus.text = "Reading files..."

        CoroutineScope(Dispatchers.Main).launch {
            val vehicles = withContext(Dispatchers.IO) { readAllLines(vehicleUris) }
            val phones = withContext(Dispatchers.IO) { readAllLines(phoneUris) }
            if (vehicles.isEmpty() || phones.isEmpty()) {
                progress.visibility = View.GONE
                tvStatus.text = "One of the files was empty."
                return@launch
            }

            tvStatus.text = "Mixing ${vehicles.size} vehicles with ${phones.size} phones..."
            val result = withContext(Dispatchers.Default) {
                Mixer.mix(vehicles, phones, skipRate, reuseCount, reuseTimes, Random())
            }

            tvStatus.text = "Writing CSV (${result.pairs.size} rows)..."
            val uri = withContext(Dispatchers.IO) { saveCsvToDownloads(result) }

            progress.visibility = View.GONE
            if (uri == null) {
                tvStatus.text = "Failed to write the output file."
                return@launch
            }
            lastOutputUri = uri
            btnShare.visibility = View.VISIBLE

            val counts = result.pairs.groupingBy { it.second }.eachCount()
            val reused = counts.count { it.value > 1 }
            val skipPct = result.skipped * 100.0 / vehicles.size
            tvStatus.text = "Done!\n" +
                    "Vehicles in: ${vehicles.size}\n" +
                    "Skipped: ${result.skipped} (%.1f%%)\n".format(skipPct) +
                    "Paired rows: ${result.pairs.size}\n" +
                    "Unique phones: ${counts.size}\n" +
                    "Reused phones: $reused\n" +
                    "Saved to Downloads as " + queryName(uri)
        }
    }

    private fun readAllLines(uris: List<Uri>): List<String> {
        val out = ArrayList<String>()
        for (u in uris) {
            try {
                contentResolver.openInputStream(u)?.bufferedReader()?.use { r ->
                    var line = r.readLine()
                    while (line != null) { out.add(line); line = r.readLine() }
                }
            } catch (_: Exception) { }
        }
        return out
    }

    private fun saveCsvToDownloads(result: Mixer.Result): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val name = "output_${System.currentTimeMillis()}.csv"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "text/csv")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return null
        contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { w ->
            w.append("VehNum,PhoneNum\n")
            for ((v, p) in result.pairs) {
                w.append(csv(v)).append(',').append(csv(p)).append('\n')
            }
        } ?: return null
        return uri
    }

    private fun csv(s: String): String =
        if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' })
            "\"" + s.replace("\"", "\"\"") + "\"" else s

    private fun shareLast() {
        val uri = lastOutputUri ?: return
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(i, "Share output CSV"))
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}

// ---------------- mixing logic (port of mix_contacts.py) ----------------

object Mixer {

    class Result(val pairs: List<Pair<String, String>>, val skipped: Int)

    fun mix(
        vehiclesRaw: List<String>,
        phonesRaw: List<String>,
        skipRate: Double,
        reuseCount: Int,
        reuseTimes: Int,
        rnd: Random
    ): Result {
        val vehicles = vehiclesRaw.map { it.trim() }.filter { it.isNotEmpty() }
        val phones = phonesRaw.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (vehicles.isEmpty() || phones.isEmpty()) return Result(emptyList(), 0)

        // 1) decide which vehicles get skipped
        val keptIdx = ArrayList<Int>(vehicles.size)
        for (i in vehicles.indices) {
            if (rnd.nextDouble() * 100 >= skipRate) keptIdx.add(i)
        }
        val kept = keptIdx.size
        val skipped = vehicles.size - kept

        // 2) shuffled phones, wrapped around if vehicles outnumber phones
        val shuffled = phones.toMutableList()
        for (i in shuffled.size - 1 downTo 1) {
            val j = rnd.nextInt(i + 1)
            val t = shuffled[i]; shuffled[i] = shuffled[j]; shuffled[j] = t
        }
        val assign = ArrayList<String>(kept)
        for (i in 0 until kept) assign.add(shuffled[i % shuffled.size])

        // 3) force-reuse: `reuseCount` phones appear exactly `reuseTimes` times
        if (reuseCount > 0 && reuseTimes > 1 && kept >= reuseTimes) {
            val unique = assign.distinct()
            val nLucky = minOf(reuseCount, unique.size)
            val luckyPick = unique.indices.shuffled(Random(rnd.nextLong())).take(nLucky)
            for (li in luckyPick) {
                val lp = unique[li]
                var need = reuseTimes - assign.count { it == lp }
                if (need <= 0) continue
                val candidates = assign.indices.filter { assign[it] != lp }
                val take = candidates.shuffled(Random(rnd.nextLong())).take(minOf(need, candidates.size))
                for (i in take) assign[i] = lp
            }
        }

        // 4) pair up (skipped vehicles are omitted)
        val pairs = ArrayList<Pair<String, String>>(kept)
        for ((j, vi) in keptIdx.withIndex()) {
            pairs.add(vehicles[vi] to assign[j])
        }
        return Result(pairs, skipped)
    }
}
