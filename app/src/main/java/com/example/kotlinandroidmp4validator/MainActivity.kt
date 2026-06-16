package com.example.kotlinandroidmp4validator

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.kotlinandroidmp4validator.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val adapter = ResultAdapter()
    private var validationJob: Job? = null
    private var timerJob: Job? = null
    private var startTimeMs = 0L
    private var isRunning = false

    private val assetDirs = listOf("encoded_videos", "filler")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupListeners()
        showAssetInfo()
    }

    private fun setupRecyclerView() {
        binding.rvResults.layoutManager = LinearLayoutManager(this)
        binding.rvResults.adapter = adapter
        binding.rvResults.addItemDecoration(
            DividerItemDecoration(this, DividerItemDecoration.VERTICAL)
        )
    }

    private fun setupListeners() {
        binding.btnStart.setOnClickListener {
            if (isRunning) cancelValidation() else startValidation()
        }
        binding.btnExport.setOnClickListener { exportReport() }

        listOf(
            binding.cbShowValid to Severity.VALID,
            binding.cbShowWarning to Severity.WARNING,
            binding.cbShowInvalid to Severity.INVALID
        ).forEach { (checkbox, severity) ->
            checkbox.setOnCheckedChangeListener { _, isChecked ->
                adapter.setSeverityVisible(severity, isChecked)
            }
        }
    }

    private fun showAssetInfo() {
        val counts = assetDirs.map { dir ->
            val files = assets.list(dir)?.filter { it.endsWith(".mp4", ignoreCase = true) } ?: emptyList()
            "$dir: ${files.size} files"
        }
        val total = assetDirs.sumOf { dir ->
            assets.list(dir)?.count { it.endsWith(".mp4", ignoreCase = true) } ?: 0
        }
        binding.tvAssetInfo.text = "Bundled assets: $total MP4 files (${counts.joinToString(", ")})"
    }

    private fun collectAssetPaths(): List<String> {
        return assetDirs.flatMap { dir ->
            val files = assets.list(dir) ?: emptyArray()
            files.filter { it.endsWith(".mp4", ignoreCase = true) }
                .sorted()
                .map { "$dir/$it" }
        }
    }

    private fun startValidation() {
        adapter.clear()
        binding.layoutSummary.visibility = View.GONE
        binding.btnExport.isEnabled = false
        setRunningState(true)

        validationJob = lifecycleScope.launch {
            binding.layoutProgress.visibility = View.VISIBLE
            binding.tvCurrentFile.text = "Collecting asset files..."
            binding.tvProgress.text = "Scanning..."

            val assetPaths = withContext(Dispatchers.IO) { collectAssetPaths() }

            if (assetPaths.isEmpty()) {
                Toast.makeText(this@MainActivity, "No MP4 files found in assets", Toast.LENGTH_SHORT).show()
                setRunningState(false)
                return@launch
            }

            val totalFiles = assetPaths.size
            binding.progressBar.max = totalFiles
            binding.progressBar.progress = 0
            startTimeMs = System.currentTimeMillis()

            timerJob = launch {
                while (isActive) {
                    updateElapsedTime()
                    delay(500)
                }
            }

            var validCount = 0
            var warningCount = 0
            var invalidCount = 0

            for ((index, assetPath) in assetPaths.withIndex()) {
                if (!isActive) break

                binding.tvCurrentFile.text = assetPath.substringAfterLast("/")
                binding.tvProgress.text = buildString {
                    append("${index + 1}/$totalFiles")
                    append(" (${(index + 1) * 100 / totalFiles}%)")
                    append("  |  OK:$validCount  WARN:$warningCount  NG:$invalidCount")
                }

                val result = withContext(Dispatchers.IO) {
                    Mp4Validator.validate(assets, assetPath)
                }

                when (result.status.severity) {
                    Severity.VALID -> validCount++
                    Severity.WARNING -> warningCount++
                    Severity.INVALID -> invalidCount++
                }

                val inserted = adapter.addResult(result)
                binding.progressBar.progress = index + 1

                if (inserted) {
                    binding.rvResults.scrollToPosition(adapter.itemCount - 1)
                }
            }

            timerJob?.cancel()
            val totalTimeMs = System.currentTimeMillis() - startTimeMs
            updateElapsedTime()
            showSummary(adapter.getAllResults(), totalTimeMs)
            binding.tvCurrentFile.text = "Validation complete!"
            binding.btnExport.isEnabled = true
            setRunningState(false)
        }
    }

    private fun cancelValidation() {
        validationJob?.cancel()
        timerJob?.cancel()

        val results = adapter.getAllResults()
        if (results.isNotEmpty()) {
            val totalTimeMs = System.currentTimeMillis() - startTimeMs
            showSummary(results, totalTimeMs)
            binding.tvCurrentFile.text = "Validation cancelled"
            binding.btnExport.isEnabled = true
        }
        setRunningState(false)
    }

    private fun setRunningState(running: Boolean) {
        isRunning = running
        binding.btnStart.text = if (running) "Cancel" else "Start Validation"
        if (running) binding.layoutProgress.visibility = View.VISIBLE
    }

    private fun updateElapsedTime() {
        val elapsed = System.currentTimeMillis() - startTimeMs
        binding.tvElapsed.text = formatDuration(elapsed)
    }

    private fun showSummary(results: List<ValidationResult>, totalTimeMs: Long) {
        val total = results.size
        val sev = severityCounts(results)
        val valid = sev[Severity.VALID] ?: 0
        val warning = sev[Severity.WARNING] ?: 0
        val invalid = sev[Severity.INVALID] ?: 0
        val empty = results.count { it.status == ValidationStatus.EMPTY_FILE }
        val notFound = results.count { it.status == ValidationStatus.NOT_FOUND }
        val noDuration = results.count { it.status == ValidationStatus.INVALID_MP4_NO_DURATION }
        val frameFail = results.count { it.status == ValidationStatus.INVALID_MP4_FRAME_DECODE_FAILED }
        val exception = results.count { it.status == ValidationStatus.INVALID_MP4_EXCEPTION }
        val avgMs = if (total > 0) totalTimeMs / total else 0

        binding.layoutSummary.visibility = View.VISIBLE

        binding.tvSummaryLine1.text =
            "Total: $total  |  Valid: $valid  |  Warning: $warning  |  Failed: $invalid"

        binding.tvSummaryLine2.text = buildString {
            append("Empty: $empty  |  Not Found: $notFound  |  ")
            append("No Duration: $noDuration  |  Frame Fail: $frameFail  |  Exception: $exception")
        }

        binding.tvSummaryLine3.text = buildString {
            append("Time: ${formatDuration(totalTimeMs)}  |  Avg: ${avgMs}ms/file")
            if (totalTimeMs > 0) {
                append("  |  ${"%.1f".format(total * 1000.0 / totalTimeMs)} files/sec")
            }
        }
    }

    private fun exportReport() {
        val results = adapter.getAllResults()
        if (results.isEmpty()) {
            Toast.makeText(this, "No results to export", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            val totalTimeMs = System.currentTimeMillis() - startTimeMs
            val report = withContext(Dispatchers.IO) { generateReport(results, totalTimeMs) }

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val reportFile = File(getExternalFilesDir(null), "mp4_validation_report_$timestamp.txt")

            withContext(Dispatchers.IO) { reportFile.writeText(report) }

            Toast.makeText(
                this@MainActivity,
                "Report saved: ${reportFile.absolutePath}",
                Toast.LENGTH_LONG
            ).show()

            // Also offer share
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "MP4 Validation Report - $timestamp")
                putExtra(Intent.EXTRA_TEXT, report)
            }
            startActivity(Intent.createChooser(shareIntent, "Share Report"))
        }
    }

    private fun generateReport(results: List<ValidationResult>, totalTimeMs: Long): String {
        val total = results.size
        val sev = severityCounts(results)
        val valid = sev[Severity.VALID] ?: 0
        val warning = sev[Severity.WARNING] ?: 0
        val invalid = sev[Severity.INVALID] ?: 0
        val sb = StringBuilder()

        sb.appendLine("=".repeat(60))
        sb.appendLine("       MP4 VALIDATION REPORT")
        sb.appendLine("=".repeat(60))
        sb.appendLine()
        sb.appendLine("Date:      ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
        sb.appendLine("Device:    ${Build.MANUFACTURER} ${Build.MODEL}")
        sb.appendLine("Android:   ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine("Source:    Bundled assets (${assetDirs.joinToString(", ")})")
        sb.appendLine()

        sb.appendLine("=".repeat(60))
        sb.appendLine("       SUMMARY")
        sb.appendLine("=".repeat(60))
        sb.appendLine()
        val pct = { n: Int -> if (total > 0) n * 100 / total else 0 }
        sb.appendLine("Total Files:     $total")
        sb.appendLine("Valid:           $valid (${pct(valid)}%)")
        sb.appendLine("Warning:         $warning (${pct(warning)}%)")
        sb.appendLine("Invalid:         $invalid (${pct(invalid)}%)")

        val byStatus = results.groupBy { it.status }
        for (status in ValidationStatus.entries) {
            val count = byStatus[status]?.size ?: 0
            if (count > 0 && status.isFailure) {
                sb.appendLine("  - ${status.label}: $count")
            }
        }

        sb.appendLine()
        sb.appendLine("Total Time:      ${formatDuration(totalTimeMs)}")
        val avgMs = if (total > 0) totalTimeMs / total else 0
        sb.appendLine("Avg Per File:    ${avgMs}ms")
        if (totalTimeMs > 0) {
            sb.appendLine("Speed:           ${"%.1f".format(total * 1000.0 / totalTimeMs)} files/sec")
        }

        val validationTimes = results.map { it.validationTimeMs }
        if (validationTimes.isNotEmpty()) {
            sb.appendLine("Min Validation:  ${validationTimes.min()}ms")
            sb.appendLine("Max Validation:  ${validationTimes.max()}ms")
            sb.appendLine("Median:          ${validationTimes.sorted()[validationTimes.size / 2]}ms")
        }

        // Failed files detail
        val failures = results.filter { !it.isValid }
        if (failures.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("=".repeat(60))
            sb.appendLine("       FAILED FILES (${failures.size})")
            sb.appendLine("=".repeat(60))

            for (result in failures) {
                sb.appendLine()
                sb.appendLine("[${result.status.label}] ${result.fileName}")
                sb.appendLine("  Asset:    ${result.filePath}")
                sb.appendLine("  Size:     ${result.fileSize} bytes (${result.formatFileSize()})")
                if (result.durationMs != null) {
                    sb.appendLine("  Duration: ${result.durationMs}ms")
                }
                if (result.errorMessage != null) {
                    sb.appendLine("  Error:    ${result.errorMessage}")
                }
                sb.appendLine("  Time:     ${result.validationTimeMs}ms")
            }
        }

        // All files table
        sb.appendLine()
        sb.appendLine("=".repeat(60))
        sb.appendLine("       ALL FILES")
        sb.appendLine("=".repeat(60))
        sb.appendLine()
        sb.appendLine("%-5s %-12s %-40s %10s %10s %8s".format(
            "#", "Status", "File Name", "Size", "Duration", "Time"
        ))
        sb.appendLine("-".repeat(90))

        for ((index, result) in results.withIndex()) {
            sb.appendLine("%-5d %-12s %-40s %10s %10s %6dms".format(
                index + 1,
                result.status.label,
                result.fileName.take(40),
                result.formatFileSize(),
                result.durationMs?.let { "${it}ms" } ?: "-",
                result.validationTimeMs
            ))
        }

        sb.appendLine()
        sb.appendLine("=".repeat(60))
        sb.appendLine("       END OF REPORT")
        sb.appendLine("=".repeat(60))

        return sb.toString()
    }

    companion object {
        private fun severityCounts(results: List<ValidationResult>): Map<Severity, Int> =
            results.groupingBy { it.status.severity }.eachCount()

        fun formatDuration(ms: Long): String {
            val seconds = ms / 1000
            val minutes = seconds / 60
            val hours = minutes / 60
            return when {
                hours > 0 -> "%d:%02d:%02d".format(hours, minutes % 60, seconds % 60)
                else -> "%d:%02d".format(minutes, seconds % 60)
            }
        }
    }
}
