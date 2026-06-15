package com.example.kotlinandroidmp4validator

enum class Severity { VALID, WARNING, INVALID }

enum class ValidationStatus(val label: String, val severity: Severity) {
    VALID("Valid", Severity.VALID),
    VALID_TAIL_VERIFIED("Valid (tail-verified)", Severity.WARNING),
    NOT_FOUND("Not Found", Severity.INVALID),
    EMPTY_FILE("Empty File", Severity.INVALID),
    INVALID_MP4_NO_DURATION("No Duration", Severity.INVALID),
    INVALID_MP4_FRAME_DECODE_FAILED("Frame Decode Failed", Severity.INVALID),
    INVALID_MP4_EXCEPTION("Exception", Severity.INVALID);

    val isFailure: Boolean get() = severity == Severity.INVALID
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
    val isValid: Boolean get() = !status.isFailure

    fun formatFileSize(): String {
        return when {
            fileSize < 1024 -> "${fileSize}B"
            fileSize < 1024 * 1024 -> "%.1fKB".format(fileSize / 1024.0)
            fileSize < 1024 * 1024 * 1024 -> "%.1fMB".format(fileSize / (1024.0 * 1024))
            else -> "%.2fGB".format(fileSize / (1024.0 * 1024 * 1024))
        }
    }
}
