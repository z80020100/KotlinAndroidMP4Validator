package com.example.kotlinandroidmp4validator

import android.content.res.AssetManager
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.util.Log
import java.io.FileDescriptor
import java.nio.ByteBuffer

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
 *
 * On slow devices getFrameAtTime can also return null for perfectly intact files:
 * the AOSP FrameDecoder shares one cumulative output-stall budget (50 x 10ms) across
 * the whole keyframe-to-target decode and bails with -EAGAIN when it runs out. To avoid
 * that false positive, a null frame is arbitrated by a decode-free container-level tail
 * integrity check before the file is declared invalid.
 */
object Mp4Validator {

    private const val TAG = "Mp4Validator"
    private const val VALIDATION_SEEK_OFFSET_MS = 100L

    // Decode-free tail integrity check, used as a fallback when getFrameAtTime times out.
    private const val TAIL_WINDOW_US = 3_000_000L         // walk the last ~3s of video samples (exceeds the 2s GOP)
    private const val TAIL_END_TOLERANCE_US = 200_000L    // last sample within 200ms of track end (> one frame interval)
    private const val DEFAULT_SAMPLE_BUFFER_BYTES = 2 * 1024 * 1024  // used when KEY_MAX_INPUT_SIZE is absent

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
                // A null frame is ambiguous: a real truncated/zero-filled mdat looks the
                // same as a transient FrameDecoder stall-budget timeout on a slow device.
                // Arbitrate with a decode-free container walk of the tail before failing.
                val tailIntact = try {
                    assetManager.openFd(assetPath).use { tailAfd ->
                        isTailIntact(tailAfd.fileDescriptor, tailAfd.startOffset, tailAfd.length)
                    }
                } catch (e: Exception) {
                    false
                }

                val status: ValidationStatus
                val errorMessage: String?
                if (tailIntact) {
                    status = ValidationStatus.VALID
                    errorMessage = null
                    Log.w(TAG, "Frame decode timed out but tail is intact (decoder flakiness, file OK): $assetPath")
                } else {
                    status = ValidationStatus.INVALID_MP4_FRAME_DECODE_FAILED
                    errorMessage = "Frame decode failed and tail integrity check confirmed missing/zero-filled data near ${seekTimeUs / 1000}ms (of ${durationMs}ms)"
                    Log.w(TAG, "Frame decode failed and tail check confirmed truncated/zero-filled mdat: $assetPath")
                }
                return ValidationResult(
                    fileName = fileName,
                    filePath = assetPath,
                    fileSize = fileSize,
                    status = status,
                    durationMs = durationMs,
                    validationTimeMs = elapsedMs(startNanos),
                    errorMessage = errorMessage
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

    /**
     * Decode-free integrity probe for the tail of the video track.
     *
     * Walks the last [TAIL_WINDOW_US] of video samples with [MediaExtractor] (container
     * parsing only, no codec) to tell a genuinely truncated/zero-filled download apart
     * from a transient decoder timeout. The tail is intact when video samples remain
     * readable through to the end of the track (last sample within [TAIL_END_TOLERANCE_US]
     * of the track duration) and the final sample is structurally valid. For AVC validity
     * means a self-consistent NAL layout ([isValidNalStructure]) which also rejects non-zero
     * garbage that a plain emptiness check would pass; non-AVC tracks fall back to a non-zero
     * byte check. When the track duration is unknown the end-position test is skipped and
     * only sample presence and validity are required.
     *
     * Visible (internal) for instrumented testing.
     */
    internal fun isTailIntact(fd: FileDescriptor, startOffset: Long, length: Long): Boolean {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(fd, startOffset, length)

            val videoTrack = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i)
                    .getString(MediaFormat.KEY_MIME)
                    ?.startsWith("video/") == true
            } ?: return false
            extractor.selectTrack(videoTrack)

            val format = extractor.getTrackFormat(videoTrack)
            val trackDurationUs =
                if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION)
                else -1L
            val bufferBytes = maxOf(
                if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0,
                DEFAULT_SAMPLE_BUFFER_BYTES
            )
            val buffer = ByteBuffer.allocate(bufferBytes)
            val lengthSize = nalLengthSize(format)

            val seekToUs = if (trackDurationUs > TAIL_WINDOW_US) trackDurationUs - TAIL_WINDOW_US else 0L
            extractor.seekTo(seekToUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            var samplesRead = 0
            var lastSampleTimeUs = -1L
            var lastSampleValid = false
            while (true) {
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                samplesRead++
                lastSampleTimeUs = extractor.sampleTime
                lastSampleValid = if (lengthSize > 0) isValidNalStructure(buffer, size, lengthSize)
                    else hasNonZeroByte(buffer, size)
                if (!extractor.advance()) break
            }

            if (samplesRead == 0 || !lastSampleValid) {
                false
            } else {
                trackDurationUs < 0 || lastSampleTimeUs >= trackDurationUs - TAIL_END_TOLERANCE_US
            }
        } catch (e: Exception) {
            false
        } finally {
            try { extractor.release() } catch (_: Exception) {}
        }
    }

    private fun hasNonZeroByte(buffer: ByteBuffer, size: Int): Boolean =
        (0 until size).any { buffer.get(it) != 0.toByte() }

    /**
     * NAL length-prefix size in bytes for an AVC track, read from the avcC record in "csd-0".
     * Returns 0 when the track is not AVC or the record is absent/unrecognised, signalling the
     * caller to fall back to [hasNonZeroByte] rather than risk misreading the sample layout.
     */
    private fun nalLengthSize(format: MediaFormat): Int {
        if (format.getString(MediaFormat.KEY_MIME) != MediaFormat.MIMETYPE_VIDEO_AVC) return 0
        return avcNalLengthSize(format.getByteBuffer("csd-0"))
    }

    /**
     * NAL length-prefix size in bytes parsed from an avcC record (AVCDecoderConfigurationRecord):
     * byte[0] must be configurationVersion 1 and byte[4]'s low 2 bits are lengthSizeMinusOne.
     * Returns 0 for an absent or unrecognised record. Visible (internal) for unit testing.
     */
    internal fun avcNalLengthSize(csd: ByteBuffer?): Int {
        if (csd == null || csd.remaining() < 5 || csd.get(csd.position()).toInt() != 1) return 0
        return (csd.get(csd.position() + 4).toInt() and 0x03) + 1
    }

    /**
     * Checks that a length-prefixed (AVCC) sample is internally consistent: every NAL length is
     * positive and within bounds, each NAL header has a clear forbidden_zero_bit and a non-zero
     * unit type, and the lengths tile the sample exactly. Non-zero garbage left by an interrupted
     * download almost always violates one of these, which [hasNonZeroByte] cannot detect.
     *
     * Visible (internal) for unit testing.
     */
    internal fun isValidNalStructure(buffer: ByteBuffer, size: Int, lengthSize: Int): Boolean {
        var pos = 0
        while (pos + lengthSize <= size) {
            var nalLen = 0L
            for (i in 0 until lengthSize) {
                nalLen = (nalLen shl 8) or (buffer.get(pos + i).toLong() and 0xFF)
            }
            val headerPos = pos + lengthSize
            if (nalLen <= 0 || headerPos + nalLen > size) return false
            val header = buffer.get(headerPos).toInt()
            if (header and 0x80 != 0) return false      // forbidden_zero_bit must be 0
            if (header and 0x1F == 0) return false       // nal_unit_type 0 is unspecified
            pos = (headerPos + nalLen).toInt()
        }
        return pos == size
    }

    private fun elapsedMs(startNanos: Long): Long {
        return (System.nanoTime() - startNanos) / 1_000_000
    }
}
