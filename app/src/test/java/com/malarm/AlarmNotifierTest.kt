package com.malarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The ringing notification carries a single Dismiss action: there is no
 * snooze, and recording a dream happens on the full-screen alarm.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AlarmNotifierTest {

    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun ringingNotificationHasOnlyDismissAction() {
        val notification = AlarmNotifier.build(context, Alarm(1, 8, 0, label = "Morning"))
        val actions = notification.actions
        assertNotNull(actions)
        assertEquals(1, actions!!.size)
        assertEquals(context.getString(R.string.dismiss), actions[0].title.toString())
    }

    @Test
    fun ringingNotificationCarriesFullScreenContentIntent() {
        val notification = AlarmNotifier.build(context, Alarm(1, 8, 0))
        assertNotNull(notification.contentIntent)
    }
}
