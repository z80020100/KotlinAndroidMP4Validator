package com.example.kotlinandroidmp4validator

enum class ValidationStatus(val label: String) {
    VALID("Valid"),
    NOT_FOUND("Not Found"),
    EMPTY_FILE("Empty File"),
    INVALID_MP4_NO_DURATION("No Duration"),
    INVALID_MP4_FRAME_DECODE_FAILED("Frame Decode Failed"),
    INVALID_MP4_EXCEPTION("Exception");

    val isFailure: Boolean get() = this != VALID
}

data class ValidationResult(
    val fileName: String,
    val filePath: String,
    val fileSize: Long,
    val status: ValidationStatus,
    val durationMs: Long? = null,
    val validationTimeMs: Long,
    val errorMessage: String? = null
) {
    val isValid: Boolean get() = status == ValidationStatus.VALID

    fun formatFileSize(): String {
        return when {
            fileSize < 1024 -> "${fileSize}B"
            fileSize < 1024 * 1024 -> "%.1fKB".format(fileSize / 1024.0)
            fileSize < 1024 * 1024 * 1024 -> "%.1fMB".format(fileSize / (1024.0 * 1024))
            else -> "%.2fGB".format(fileSize / (1024.0 * 1024 * 1024))
        }
    }
}
