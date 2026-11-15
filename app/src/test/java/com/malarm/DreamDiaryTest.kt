package com.malarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DreamDiaryTest {

    private val context get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        DreamDiary.diaryFile(context).delete()
    }

    @Test
    fun readIsEmptyWithoutADiary() {
        assertEquals("", DreamDiary.read(context))
    }

    @Test
    fun entryHasTimestampHeadingAndPendingLine() {
        val entry = DreamDiary.formatEntry("dream-x.wav", 0L, 12_500L)
        assertTrue(entry.startsWith("## "))
        assertTrue(entry.contains(DreamDiary.pendingLine("dream-x.wav", 12_500L)))
        assertTrue(entry.contains("12s"))
        assertTrue(entry.endsWith("\n\n"))
    }

    @Test
    fun appendEntryAddsEntriesInOrder() {
        val first = DreamDiary.audioFile(context, "dream-a.wav")
        val second = DreamDiary.audioFile(context, "dream-b.wav")
        DreamDiary.appendEntry(context, first, 0L, 5_000L)
        DreamDiary.appendEntry(context, second, 60_000L, 6_000L)

        val text = DreamDiary.read(context)
        assertEquals(2, Regex("(?m)^## ").findAll(text).count())
        assertTrue(text.indexOf("dream-a.wav") in 0 until text.indexOf("dream-b.wav"))
    }

    @Test
    fun newAudioFileIsAWavInsideTheDreamsFolder() {
        val file = DreamDiary.newAudioFile(context, 0L)
        assertTrue(file.name.startsWith("dream-"))
        assertTrue(file.name.endsWith(".wav"))
        assertEquals("dreams", file.parentFile?.name)
    }
}
