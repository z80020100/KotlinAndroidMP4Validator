package com.example.kotlinandroidmp4validator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/**
 * JVM unit tests for [Mp4Validator.isValidNalStructure] — the AVCC sample sanity check that lets
 * the tail integrity probe reject non-zero garbage a plain emptiness check would accept. Pure
 * ByteBuffer logic with no Android framework, so it runs on the JVM without a device.
 */
class Mp4ValidatorNalStructureTest {

    private val lengthSize = 4

    /** Builds a length-prefixed NAL: [4-byte length][header][payload], header carrying [type]. */
    private fun nal(type: Int, payloadSize: Int): ByteArray {
        val body = ByteArray(1 + payloadSize)
        body[0] = (type and 0x1F).toByte()                 // forbidden_zero_bit 0, unit type in header
        for (i in 1 until body.size) body[i] = (i and 0xFF).toByte()
        val len = body.size
        return byteArrayOf(
            (len ushr 24).toByte(), (len ushr 16).toByte(), (len ushr 8).toByte(), len.toByte()
        ) + body
    }

    private fun valid(data: ByteArray) =
        Mp4Validator.isValidNalStructure(ByteBuffer.wrap(data), data.size, lengthSize)

    @Test
    fun singleWellFormedNal_isValid() {
        assertTrue(valid(nal(type = 5, payloadSize = 32)))
    }

    @Test
    fun multipleWellFormedNals_areValid() {
        assertTrue(valid(nal(7, 8) + nal(8, 4) + nal(5, 64)))
    }

    @Test
    fun allZeroSample_isRejected() {
        assertFalse(valid(ByteArray(64)))                       // length prefix 0 is not positive
    }

    @Test
    fun nonZeroGarbage_isRejected() {
        assertFalse(valid(ByteArray(64) { 0xFF.toByte() }))     // first length 0xFFFFFFFF overshoots
    }

    @Test
    fun nalLengthOvershootingSample_isRejected() {
        val data = nal(5, 16)
        data[3] = (data[3] + 32).toByte()                       // declared length now points past the sample
        assertFalse(valid(data))
    }

    @Test
    fun trailingBytesAfterLastNal_isRejected() {
        assertFalse(valid(nal(5, 16) + byteArrayOf(1, 2, 3)))   // lengths do not tile the sample exactly
    }

    @Test
    fun forbiddenZeroBitSet_isRejected() {
        val data = nal(5, 16)
        data[lengthSize] = (data[lengthSize].toInt() or 0x80).toByte()
        assertFalse(valid(data))
    }

    @Test
    fun zeroNalType_isRejected() {
        assertFalse(valid(nal(type = 0, payloadSize = 16)))
    }

    // --- avcC length-size parsing ---

    /** Minimal avcC record (configurationVersion 1) carrying [lengthSizeMinusOne] in byte[4]. */
    private fun avcRecord(lengthSizeMinusOne: Int): ByteBuffer =
        ByteBuffer.wrap(byteArrayOf(1, 0x42, 0x00, 0x1E, (0xFC or (lengthSizeMinusOne and 0x03)).toByte(), 0x01))

    @Test
    fun avcNalLengthSize_readsFourByteLength() {
        assertEquals(4, Mp4Validator.avcNalLengthSize(avcRecord(lengthSizeMinusOne = 3)))
    }

    @Test
    fun avcNalLengthSize_readsSingleByteLength() {
        assertEquals(1, Mp4Validator.avcNalLengthSize(avcRecord(lengthSizeMinusOne = 0)))
    }

    @Test
    fun avcNalLengthSize_nullOrShortRecord_isZero() {
        assertEquals(0, Mp4Validator.avcNalLengthSize(null))
        assertEquals(0, Mp4Validator.avcNalLengthSize(ByteBuffer.wrap(byteArrayOf(1, 0x42))))
    }

    @Test
    fun avcNalLengthSize_nonAvccRecord_isZero() {
        // Annex-B start code: configurationVersion byte is 0, not 1
        assertEquals(0, Mp4Validator.avcNalLengthSize(ByteBuffer.wrap(byteArrayOf(0, 0, 0, 1, 0x67, 0x42))))
    }
}
