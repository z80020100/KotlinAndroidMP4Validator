package com.example.kotlinandroidmp4validator

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.kotlinandroidmp4validator.databinding.ItemValidationResultBinding

class ResultAdapter : RecyclerView.Adapter<ResultAdapter.ViewHolder>() {

    private val allResults = mutableListOf<ValidationResult>()
    private var filteredResults = mutableListOf<ValidationResult>()
    private var showOnlyFailures = false

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

            when (result.status) {
                ValidationStatus.VALID -> {
                    tvStatusIcon.text = "O"
                    tvStatusIcon.setTextColor(ContextCompat.getColor(ctx, R.color.status_valid))
                    tvDetails.text = "${result.formatFileSize()} | ${result.durationMs}ms"
                    itemRoot.setBackgroundColor(Color.TRANSPARENT)
                }
                ValidationStatus.NOT_FOUND -> {
                    tvStatusIcon.text = "?"
                    tvStatusIcon.setTextColor(ContextCompat.getColor(ctx, R.color.status_not_found))
                    tvDetails.text = "File not found"
                    itemRoot.setBackgroundColor(ContextCompat.getColor(ctx, R.color.bg_invalid))
                }
                ValidationStatus.EMPTY_FILE -> {
                    tvStatusIcon.text = "X"
                    tvStatusIcon.setTextColor(ContextCompat.getColor(ctx, R.color.status_empty))
                    tvDetails.text = "Empty file (0 bytes)"
                    itemRoot.setBackgroundColor(ContextCompat.getColor(ctx, R.color.bg_invalid))
                }
                else -> {
                    tvStatusIcon.text = "X"
                    tvStatusIcon.setTextColor(ContextCompat.getColor(ctx, R.color.status_invalid))
                    tvDetails.text = "${result.formatFileSize()} | ${result.status.label}: ${result.errorMessage ?: "unknown"}"
                    itemRoot.setBackgroundColor(ContextCompat.getColor(ctx, R.color.bg_invalid))
                }
            }
        }
    }

    override fun getItemCount(): Int = filteredResults.size

    fun addResult(result: ValidationResult) {
        allResults.add(result)
        if (!showOnlyFailures || result.status.isFailure) {
            filteredResults.add(result)
            notifyItemInserted(filteredResults.size - 1)
        }
    }

    fun setShowOnlyFailures(show: Boolean) {
        showOnlyFailures = show
        refilter()
    }

    fun getAllResults(): List<ValidationResult> = allResults.toList()

    fun clear() {
        allResults.clear()
        filteredResults.clear()
        notifyDataSetChanged()
    }

    private fun refilter() {
        filteredResults = if (showOnlyFailures) {
            allResults.filter { it.status.isFailure }.toMutableList()
        } else {
            allResults.toMutableList()
        }
        notifyDataSetChanged()
    }
}
