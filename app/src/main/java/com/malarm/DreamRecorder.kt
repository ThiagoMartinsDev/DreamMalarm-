package com.malarm

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Records the microphone to [file] as 16 kHz mono 16-bit PCM WAV, the format
 * whisper.cpp expects, so transcription needs no decoding step.
 *
 * The audio is written to disk as it arrives. If the process dies mid-recording
 * the file is still there (its WAV header sizes are only patched on stop, so a
 * reader should trust the file length, see [DreamDiary]).
 */
class DreamRecorder(private val file: File) {

    private var record: AudioRecord? = null
    private var thread: Thread? = null

    @Volatile private var recording = false
    @Volatile private var bytesWritten = 0L

    val isRecording: Boolean get() = recording

    /** Starts recording. Returns false if the microphone could not be opened. The caller must hold RECORD_AUDIO. */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (recording) return true
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) return false
        val bufferSize = maxOf(minBuffer, SAMPLE_RATE) // about half a second of audio
        val audioRecord = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
            )
        } catch (e: IllegalArgumentException) {
            return false
        } catch (e: SecurityException) {
            return false
        }
        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            return false
        }
        val out = try {
            file.parentFile?.mkdirs()
            RandomAccessFile(file, "rw").also {
                it.setLength(0)
                it.write(wavHeader(0))
            }
        } catch (e: IOException) {
            audioRecord.release()
            return false
        }
        try {
            audioRecord.startRecording()
        } catch (e: IllegalStateException) {
            audioRecord.release()
            out.close()
            return false
        }
        if (audioRecord.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            audioRecord.release()
            out.close()
            return false
        }
        record = audioRecord
        bytesWritten = 0L
        recording = true
        thread = Thread({ pump(audioRecord, out, bufferSize) }, "dream-recorder").also { it.start() }
        return true
    }

    /** Stops recording, finalizes the WAV header and returns the recorded duration in milliseconds. */
    fun stop(): Long {
        if (!recording) return durationMs()
        recording = false
        val audioRecord = record
        try {
            audioRecord?.stop()
        } catch (e: IllegalStateException) {
            // Already stopped; the pump loop ends on its own.
        }
        try {
            thread?.join(JOIN_TIMEOUT_MS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        audioRecord?.release()
        record = null
        thread = null
        return durationMs()
    }

    private fun durationMs(): Long = bytesWritten * 1000L / BYTES_PER_SECOND

    private fun pump(audioRecord: AudioRecord, out: RandomAccessFile, bufferSize: Int) {
        val buffer = ByteArray(bufferSize)
        try {
            while (recording) {
                val read = audioRecord.read(buffer, 0, buffer.size)
                if (read > 0) {
                    out.write(buffer, 0, read)
                    bytesWritten += read
                } else if (read < 0) {
                    break
                }
            }
        } catch (e: IOException) {
            // Disk problem: keep what was written so far.
        } finally {
            try {
                out.seek(0)
                out.write(wavHeader(bytesWritten))
            } catch (e: IOException) {
                // Header stays zero-length; readers fall back to the file size.
            }
            try {
                out.close()
            } catch (e: IOException) {
                // Nothing more to do.
            }
        }
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val BYTES_PER_SECOND = SAMPLE_RATE * 2L
        private const val JOIN_TIMEOUT_MS = 3_000L
        const val WAV_HEADER_SIZE = 44

        /** A canonical 44-byte PCM WAV header for [dataBytes] bytes of 16 kHz mono 16-bit audio. */
        internal fun wavHeader(dataBytes: Long): ByteArray {
            val header = ByteBuffer.allocate(WAV_HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray(Charsets.US_ASCII))
            header.putInt((36 + dataBytes).toInt())
            header.put("WAVE".toByteArray(Charsets.US_ASCII))
            header.put("fmt ".toByteArray(Charsets.US_ASCII))
            header.putInt(16) // PCM fmt chunk size
            header.putShort(1) // PCM
            header.putShort(1) // mono
            header.putInt(SAMPLE_RATE)
            header.putInt(SAMPLE_RATE * 2) // byte rate
            header.putShort(2) // block align
            header.putShort(16) // bits per sample
            header.put("data".toByteArray(Charsets.US_ASCII))
            header.putInt(dataBytes.toInt())
            return header.array()
        }
    }
}
