package com.malarm

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Test

class DreamRecorderTest {

    @Test
    fun wavHeaderDescribes16kHzMono16BitPcm() {
        val dataBytes = 32_000L
        val header = DreamRecorder.wavHeader(dataBytes)
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)

        assertEquals(44, header.size)
        assertEquals("RIFF", String(header, 0, 4, Charsets.US_ASCII))
        assertEquals(36 + dataBytes.toInt(), buffer.getInt(4))
        assertEquals("WAVE", String(header, 8, 4, Charsets.US_ASCII))
        assertEquals("fmt ", String(header, 12, 4, Charsets.US_ASCII))
        assertEquals(16, buffer.getInt(16))
        assertEquals(1, buffer.getShort(20).toInt()) // PCM
        assertEquals(1, buffer.getShort(22).toInt()) // mono
        assertEquals(16_000, buffer.getInt(24))
        assertEquals(32_000, buffer.getInt(28)) // byte rate
        assertEquals(2, buffer.getShort(32).toInt()) // block align
        assertEquals(16, buffer.getShort(34).toInt()) // bits per sample
        assertEquals("data", String(header, 36, 4, Charsets.US_ASCII))
        assertEquals(dataBytes.toInt(), buffer.getInt(40))
    }

    @Test
    fun emptyRecordingHasZeroLengthHeader() {
        val buffer = ByteBuffer.wrap(DreamRecorder.wavHeader(0)).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(36, buffer.getInt(4))
        assertEquals(0, buffer.getInt(40))
    }
}
