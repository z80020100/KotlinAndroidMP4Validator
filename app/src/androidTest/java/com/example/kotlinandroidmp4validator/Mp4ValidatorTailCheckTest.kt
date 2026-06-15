package com.example.kotlinandroidmp4validator

import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile

/**
 * Verifies the decode-free tail integrity check ([Mp4Validator.isTailIntact]) that
 * arbitrates a null frame decode.
 *
 * Self-contained: a short H.264/MP4 clip is encoded at runtime (no bundled assets), then
 * the deterministic logic is asserted to tell an intact tail from a zero-filled or
 * truncated one. The end-to-end recovery (decode timeout -> VALID) is inherently
 * device-specific and is verified manually on the target hardware, not here.
 */
@RunWith(AndroidJUnit4::class)
class Mp4ValidatorTailCheckTest {

    private lateinit var srcFile: File

    @Before
    fun setUp() {
        srcFile = File.createTempFile("tailsrc", ".mp4", cacheDir())
        generateVideoMp4(srcFile)
    }

    @After
    fun tearDown() {
        srcFile.delete()
    }

    @Test
    fun intactFile_isTailIntact() {
        assertTrue("a freshly encoded clip must be tail-intact", tailIntactOf(srcFile))
    }

    @Test
    fun zeroFilledSampleTail_isDetectedAsBroken() {
        // Zero the tail of mdat (the last video samples) while leaving moov intact, mimicking a
        // fast-start download whose trailing frames never arrived.
        val f = copyOf(srcFile)
        try {
            overwriteMdatTail(f, 0)
            assertFalse("zero-filled sample tail must be detected", tailIntactOf(f))
        } finally {
            f.delete()
        }
    }

    @Test
    fun nonZeroGarbageSampleTail_isDetectedAsBroken() {
        // Same fast-start truncation but the trailing samples hold non-zero garbage (e.g. stale
        // disk content). A plain emptiness check would pass this; the NAL structure check must
        // still reject it.
        val f = copyOf(srcFile)
        try {
            overwriteMdatTail(f, 0xFF.toByte())
            assertFalse("non-zero garbage sample tail must be detected", tailIntactOf(f))
        } finally {
            f.delete()
        }
    }

    @Test
    fun physicallyTruncatedFile_isDetectedAsBroken() {
        val f = copyOf(srcFile)
        try {
            RandomAccessFile(f, "rw").use { raf -> raf.setLength(raf.length() * 60 / 100) }
            assertFalse("physically truncated file must be detected", tailIntactOf(f))
        } finally {
            f.delete()
        }
    }

    // --- helpers ---

    private fun cacheDir(): File =
        InstrumentationRegistry.getInstrumentation().targetContext.cacheDir

    private fun tailIntactOf(file: File): Boolean =
        FileInputStream(file).use { Mp4Validator.isTailIntact(it.fd, 0L, file.length()) }

    private fun copyOf(src: File): File {
        val out = File.createTempFile("taildmg", ".mp4", cacheDir())
        src.inputStream().use { input -> out.outputStream().use { input.copyTo(it) } }
        return out
    }

    /** Overwrites the last 40% of the mdat payload (the trailing video samples) with [fill]. */
    private fun overwriteMdatTail(f: File, fill: Byte) {
        val mdat = boxExtent(f, "mdat")
        assertTrue("test fixture must contain an mdat box", mdat != null)
        val (payloadStart, end) = mdat!!
        RandomAccessFile(f, "rw").use { raf ->
            val from = end - (end - payloadStart) * 40 / 100   // last 40% of mdat = trailing samples
            raf.seek(from)
            raf.write(ByteArray((end - from).toInt()) { fill })
        }
    }

    /** Returns (payloadStart, end) file offsets of the first top-level box of [type], or null. */
    private fun boxExtent(file: File, type: String): Pair<Long, Long>? {
        RandomAccessFile(file, "r").use { raf ->
            val len = raf.length()
            var offset = 0L
            val header = ByteArray(8)
            while (offset + 8 <= len) {
                raf.seek(offset)
                raf.readFully(header)
                var size = 0L
                for (i in 0 until 4) size = (size shl 8) or (header[i].toLong() and 0xFF)
                var payloadStart = offset + 8
                val boxLen = when (size) {
                    1L -> {                       // 64-bit largesize follows the header
                        val large = ByteArray(8)
                        raf.readFully(large)
                        var s = 0L
                        for (b in large) s = (s shl 8) or (b.toLong() and 0xFF)
                        payloadStart = offset + 16
                        s
                    }
                    0L -> len - offset            // box extends to end of file
                    else -> size
                }
                if (String(header, 4, 4, Charsets.US_ASCII) == type) return payloadStart to (offset + boxLen)
                if (boxLen <= 0) break
                offset += boxLen
            }
        }
        return null
    }

    /** Encodes a short, multi-GOP H.264 clip into [out] as MP4 (moov written last). */
    private fun generateVideoMp4(out: File) {
        val mime = MediaFormat.MIMETYPE_VIDEO_AVC
        val format = MediaFormat.createVideoFormat(mime, WIDTH, HEIGHT).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, 2_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createEncoderByType(mime)
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            val info = MediaCodec.BufferInfo()
            var trackIndex = -1
            var muxerStarted = false
            var frameIndex = 0
            var inputDone = false

            while (true) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        if (frameIndex >= FRAME_COUNT) {
                            codec.queueInputBuffer(inIndex, 0, 0, ptsUs(frameIndex), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            fillInputImage(codec.getInputImage(inIndex)!!, frameIndex)
                            codec.queueInputBuffer(inIndex, 0, WIDTH * HEIGHT * 3 / 2, ptsUs(frameIndex), 0)
                            frameIndex++
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    trackIndex = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxerStarted = true
                } else if (outIndex >= 0) {
                    val encoded = codec.getOutputBuffer(outIndex)!!
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (muxerStarted && !isConfig && info.size > 0) {
                        encoded.position(info.offset)
                        encoded.limit(info.offset + info.size)
                        muxer.writeSampleData(trackIndex, encoded, info)
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { muxer.stop() }
            runCatching { muxer.release() }
        }
    }

    private fun fillInputImage(image: Image, frameIndex: Int) {
        for (i in image.planes.indices) {
            val buffer = image.planes[i].buffer
            val value = (32 + frameIndex * 4 + i * 48).toByte()   // non-zero, varies per frame
            while (buffer.hasRemaining()) buffer.put(value)
        }
    }

    private fun ptsUs(frameIndex: Int): Long = frameIndex * 1_000_000L / FRAME_RATE

    companion object {
        private const val WIDTH = 320
        private const val HEIGHT = 240
        private const val FRAME_RATE = 15
        private const val FRAME_COUNT = 24      // ~1.6s, I-frame interval 1s -> 2 GOPs
        private const val TIMEOUT_US = 10_000L
    }
}
