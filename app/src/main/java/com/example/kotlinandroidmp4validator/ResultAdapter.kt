package com.example.kotlinandroidmp4validator

import android.content.Context
import android.graphics.Color
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.kotlinandroidmp4validator.databinding.ItemValidationResultBinding

class ResultAdapter : RecyclerView.Adapter<ResultAdapter.ViewHolder>() {

    private val allResults = mutableListOf<ValidationResult>()
    private var filteredResults = mutableListOf<ValidationResult>()
    private val visibleSeverities = Severity.entries.toMutableSet()

    class ViewHolder(val binding: ItemValidationResultBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemValidationResultBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val result = filteredResults[position]
        val ctx = holder.itemView.context

        with(holder.binding) {
            tvFileName.text = result.fileName
            tvTime.text = "${result.validationTimeMs}ms"

            val (iconText, iconColor) = when (result.status.severity) {
                Severity.VALID -> "O" to R.color.status_valid
                Severity.WARNING -> "!" to R.color.status_warning
                Severity.INVALID -> "X" to R.color.status_invalid
            }
            tvStatusIcon.text = iconText
            tvStatusIcon.setTextColor(ContextCompat.getColor(ctx, iconColor))
            itemRoot.setBackgroundColor(
                if (result.status.isFailure) ContextCompat.getColor(ctx, R.color.bg_invalid)
                else Color.TRANSPARENT
            )

            tvDetails.text = when (result.status) {
                ValidationStatus.VALID -> "${result.formatFileSize()} | ${result.durationMs}ms"
                ValidationStatus.VALID_TAIL_VERIFIED -> "${result.formatFileSize()} | ${result.durationMs}ms | tail-verified"
                ValidationStatus.NOT_FOUND -> "File not found"
                ValidationStatus.EMPTY_FILE -> "Empty file (0 bytes)"
                else -> "${result.formatFileSize()} | ${result.status.label}: ${result.errorMessage ?: "unknown"}"
            }

            if (result.retryCount > 0) {
                tvRetryStats.visibility = View.VISIBLE
                tvRetryStats.text = buildRetryStats(ctx, result)
            } else {
                tvRetryStats.visibility = View.GONE
            }
        }
    }

    override fun getItemCount(): Int = filteredResults.size

    fun addResult(result: ValidationResult): Boolean {
        allResults.add(result)
        if (!isVisible(result)) return false
        filteredResults.add(result)
        notifyItemInserted(filteredResults.size - 1)
        return true
    }

    fun updateResult(result: ValidationResult) {
        val index = allResults.indexOfFirst { it.filePath == result.filePath }
        if (index < 0) return
        allResults[index] = result
        refilter()
    }

    fun setSeverityVisible(severity: Severity, visible: Boolean) {
        if (visible) visibleSeverities.add(severity) else visibleSeverities.remove(severity)
        refilter()
    }

    fun getAllResults(): List<ValidationResult> = allResults.toList()

    fun getResult(filePath: String): ValidationResult? =
        allResults.firstOrNull { it.filePath == filePath }

    fun clear() {
        allResults.clear()
        filteredResults.clear()
        notifyDataSetChanged()
    }

    private fun refilter() {
        filteredResults = allResults.filter(::isVisible).toMutableList()
        notifyDataSetChanged()
    }

    private fun isVisible(result: ValidationResult): Boolean =
        result.status.severity in visibleSeverities

    private fun buildRetryStats(ctx: Context, result: ValidationResult): CharSequence {
        val green = ContextCompat.getColor(ctx, R.color.status_valid)
        val red = ContextCompat.getColor(ctx, R.color.status_invalid)
        return SpannableStringBuilder().apply {
            append("retry ${result.retryCount}×: ")
            appendColored("${result.retryPassCount} pass", green)
            append(" / ")
            appendColored("${result.retryFailCount} fail", red)
        }
    }

    private fun SpannableStringBuilder.appendColored(text: String, color: Int) {
        val start = length
        append(text)
        setSpan(ForegroundColorSpan(color), start, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}
