package com.malarm

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The dream diary: a plain Markdown file plus the WAV recordings it points to,
 * both in the app's private storage (nothing is written outside the app, and
 * the app has no INTERNET permission).
 */
object DreamDiary {

    private const val DIARY_FILE = "diary.md"
    private const val AUDIO_DIR = "dreams"

    fun diaryFile(context: Context): File = File(context.filesDir, DIARY_FILE)

    fun audioFile(context: Context, name: String): File =
        File(File(context.filesDir, AUDIO_DIR), name)

    /** A new, not yet existing recording file named after [now]. */
    fun newAudioFile(context: Context, now: Long = System.currentTimeMillis()): File {
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date(now))
        return audioFile(context, "dream-$stamp.wav")
    }

    fun read(context: Context): String {
        val file = diaryFile(context)
        return if (file.exists()) file.readText() else ""
    }

    /** Appends a timestamped entry for a finished recording, marked as awaiting its transcript. */
    @Synchronized
    fun appendEntry(context: Context, audio: File, recordedAt: Long, durationMs: Long) {
        diaryFile(context).appendText(formatEntry(audio.name, recordedAt, durationMs))
    }

    internal fun formatEntry(audioName: String, recordedAt: Long, durationMs: Long): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm (EEE)", Locale.US).format(Date(recordedAt))
        return "## $stamp\n\n${pendingLine(audioName, durationMs)}\n\n"
    }

    /** The placeholder line a transcript will later replace. Unique per recording because the file name is. */
    internal fun pendingLine(audioName: String, durationMs: Long): String {
        val seconds = durationMs / 1000
        return "_Audio saved (${seconds}s), transcript pending: $audioName_"
    }
}
