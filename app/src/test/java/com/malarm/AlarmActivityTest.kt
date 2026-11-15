package com.malarm

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.content.Context
import android.os.Looper
import android.view.View
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AlarmActivityTest {

    private lateinit var context: Context
    private lateinit var store: AlarmStore

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        store = AlarmStore(context)
        context.getSharedPreferences("malarm", Context.MODE_PRIVATE).edit().clear().commit()
        runBlocking { EventLog.clear(context) }
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        shadowOf(alarmManager).scheduledAlarms.forEach { alarmManager.cancel(it.operation!!) }
    }

    private val alarmManager
        get() = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private fun awaitEvent(type: EventType): AlarmEvent {
        repeat(50) {
            val events = runBlocking { EventLog.getAll(context) }
            events.firstOrNull { it.type == type }?.let { return it }
            Thread.sleep(20)
        }
        throw AssertionError("event $type never appeared in the log")
    }

    @Test
    fun backPressWhileRingingDoesNothing() {
        // Back must not silence the alarm by accident: the screen stays up and
        // nothing is dismissed or scheduled.
        store.save(Alarm(1, 8, 0, label = "Morning"))
        val controller = Robolectric.buildActivity(
            AlarmActivity::class.java,
            AlarmActivity.intent(context, 1L),
        ).setup()
        controller.get().onBackPressedDispatcher.onBackPressed()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(controller.get().isFinishing)
        assertTrue(shadowOf(alarmManager).scheduledAlarms.isEmpty())
    }

    @Test
    fun recordWithoutMicrophonePermissionKeepsAlarmRinging() {
        // A denied (or not yet granted) microphone must never silence the alarm.
        shadowOf(context as Application).denyPermissions(Manifest.permission.RECORD_AUDIO)
        store.save(Alarm(1, 8, 0, label = "Morning"))
        val controller = Robolectric.buildActivity(
            AlarmActivity::class.java,
            AlarmActivity.intent(context, 1L),
        ).setup()
        controller.get().findViewById<android.widget.Button>(R.id.record).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(controller.get().isFinishing)
        assertEquals(
            View.VISIBLE,
            controller.get().findViewById<android.widget.Button>(R.id.dismiss).visibility,
        )
    }

    @Test
    fun missingAlarmFinishesImmediately() {
        val controller = Robolectric.buildActivity(
            AlarmActivity::class.java,
            AlarmActivity.intent(context, 999L),
        ).setup()
        assertTrue(controller.get().isFinishing)
    }

    @Test
    fun dismissButtonOnFiredOneShotStaysDisarmedAndFinishes() {
        // Manual 5.4 logic: full-screen Dismiss cancels directly and logs
        // DISMISSED with the alarm id + label. Post-fire one-shot state:
        // the receiver already disabled it, so nothing is re-armed.
        store.save(Alarm(1, 8, 0, label = "Morning", enabled = false))
        val controller = Robolectric.buildActivity(
            AlarmActivity::class.java,
            AlarmActivity.intent(context, 1L),
        ).setup()
        controller.get().findViewById<android.widget.Button>(R.id.dismiss).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(controller.get().isFinishing)
        assertTrue(shadowOf(alarmManager).scheduledAlarms.isEmpty())
        val event = awaitEvent(EventType.DISMISSED)
        assertEquals(1L, event.alarmId)
        assertEquals("Morning", event.label)
        assertEquals(null, event.details)
    }

    @Test
    fun dismissButtonRearmsRepeatingAlarmAndFinishes() {
        // Dismiss ends this instance, not the series: tomorrow's main
        // (armed at fire time) is cancelled by the dismiss and re-armed.
        store.save(Alarm(1, 8, 0, label = "Morning", repeatDays = setOf(java.util.Calendar.MONDAY)))
        AlarmScheduler(context).schedule(store.get(1)!!)
        val controller = Robolectric.buildActivity(
            AlarmActivity::class.java,
            AlarmActivity.intent(context, 1L),
        ).setup()
        controller.get().findViewById<android.widget.Button>(R.id.dismiss).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(controller.get().isFinishing)
        val scheduled = shadowOf(alarmManager).scheduledAlarms
        assertEquals(1, scheduled.size)
        assertEquals(AlarmManager.RTC_WAKEUP, scheduled.single().type)
        val event = awaitEvent(EventType.DISMISSED)
        assertEquals(1L, event.alarmId)
        assertEquals("Morning", event.label)
        assertEquals("Re-armed", event.details)
    }
}
