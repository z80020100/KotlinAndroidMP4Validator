package com.example.kotlinandroidmp4validator

import android.content.res.AssetManager
import android.media.MediaMetadataRetriever

/**
 * MP4 validation logic ported from rfs_player PR #301.
 *
 * Validates MP4 integrity by:
 * 1. Opening asset file descriptor (catches missing/corrupt files)
 * 2. Checking file is non-empty
 * 3. Extracting duration metadata (catches missing moov atom in standard MP4)
 * 4. Decoding a frame near the end of video (catches incomplete mdat in fast-start MP4)
 *
 * MP4 Structure:
 *   Standard MP4: [ftyp][mdat][moov]  - incomplete download loses moov (index)
 *   Fast-start:   [ftyp][moov][mdat]  - incomplete download loses end of mdat (frames)
 *
 * DownloadManager pre-allocates file size, so file.length() may look correct
 * even if the download was interrupted. Frame decode catches this case.
 */
object Mp4Validator {

    private const val VALIDATION_SEEK_OFFSET_MS = 100L

    fun validate(assetManager: AssetManager, assetPath: String): ValidationResult {
        val startNanos = System.nanoTime()
        val fileName = assetPath.substringAfterLast("/")

        val afd = try {
            assetManager.openFd(assetPath)
        } catch (e: Exception) {
            return ValidationResult(
                fileName = fileName,
                filePath = assetPath,
                fileSize = 0L,
                status = ValidationStatus.NOT_FOUND,
                validationTimeMs = elapsedMs(startNanos),
                errorMessage = "Cannot open asset: ${e.message}"
            )
        }

        val fileSize = afd.length
        if (fileSize == 0L) {
            afd.close()
            return ValidationResult(
                fileName = fileName,
                filePath = assetPath,
                fileSize = 0L,
                status = ValidationStatus.EMPTY_FILE,
                validationTimeMs = elapsedMs(startNanos)
            )
        }

        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)

            val durationMs = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull()

            if (durationMs == null) {
                return ValidationResult(
                    fileName = fileName,
                    filePath = assetPath,
                    fileSize = fileSize,
                    status = ValidationStatus.INVALID_MP4_NO_DURATION,
                    validationTimeMs = elapsedMs(startNanos),
                    errorMessage = "Could not extract duration metadata"
                )
            }

            // Decode a frame near the end - fails if mdat is incomplete or pre-allocated zeros
            val seekTimeUs = maxOf(0L, durationMs - VALIDATION_SEEK_OFFSET_MS) * 1000
            val frame = retriever.getFrameAtTime(
                seekTimeUs,
                MediaMetadataRetriever.OPTION_CLOSEST
            )

            if (frame == null) {
                return ValidationResult(
                    fileName = fileName,
                    filePath = assetPath,
                    fileSize = fileSize,
                    status = ValidationStatus.INVALID_MP4_FRAME_DECODE_FAILED,
                    durationMs = durationMs,
                    validationTimeMs = elapsedMs(startNanos),
                    errorMessage = "Failed to decode frame at ${seekTimeUs / 1000}ms (near end of ${durationMs}ms video)"
                )
            }

            frame.recycle()

            ValidationResult(
                fileName = fileName,
                filePath = assetPath,
                fileSize = fileSize,
                status = ValidationStatus.VALID,
                durationMs = durationMs,
                validationTimeMs = elapsedMs(startNanos)
            )
        } catch (e: Exception) {
            ValidationResult(
                fileName = fileName,
                filePath = assetPath,
                fileSize = fileSize,
                status = ValidationStatus.INVALID_MP4_EXCEPTION,
                validationTimeMs = elapsedMs(startNanos),
                errorMessage = "${e.javaClass.simpleName}: ${e.message}"
            )
        } finally {
            try { retriever.release() } catch (_: Exception) {}
            try { afd.close() } catch (_: Exception) {}
        }
    }

    private fun elapsedMs(startNanos: Long): Long {
        return (System.nanoTime() - startNanos) / 1_000_000
    }
}
