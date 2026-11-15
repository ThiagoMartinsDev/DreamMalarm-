package com.malarm

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.malarm.databinding.ActivityAlarmBinding
import java.io.File

/**
 * The full-screen alarm. There is no snooze: the alarm is either dismissed, or
 * silenced by tapping "Record dream", which records the microphone to a file
 * and adds a timestamped entry to the dream diary.
 */
class AlarmActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAlarmBinding
    private var alarm: Alarm? = null

    private var recorder: DreamRecorder? = null
    private var audioFile: File? = null
    private var recordedAt = 0L

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            startRecording()
        } else {
            // A denied permission must never silence the alarm: it keeps ringing
            // and Dismiss still works.
            Toast.makeText(this, R.string.mic_permission_needed, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAlarmBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
            window.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }

        // Keep the Record/Dismiss buttons clear of the system navigation bar.
        val baseBottom = binding.root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bottomInset = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            view.setPadding(
                view.paddingLeft,
                view.paddingTop,
                view.paddingRight,
                baseBottom + bottomInset,
            )
            insets
        }

        val id = intent.getLongExtra(AlarmScheduler.EXTRA_ALARM_ID, -1)
        alarm = AlarmStore(this).get(id)
        if (alarm == null) {
            finish()
            return
        }

        binding.label.text = alarm!!.label.ifBlank { getString(R.string.app_name) }
        binding.time.text = AlarmFormatter.time(alarm!!)
        binding.repeat.text = AlarmFormatter.repeat(this, alarm!!)

        // First tap starts recording, second tap ("Done") saves it and closes.
        binding.record.setOnClickListener {
            when {
                recorder != null -> {
                    finishRecording()
                    finish()
                }
                hasMicPermission() -> startRecording()
                else -> micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
        binding.dismiss.setOnClickListener {
            dismiss()
            finish()
        }

        // Back must not silence the alarm by accident, so while it rings it does
        // nothing (Dismiss and Record are always on screen). While recording it
        // behaves like Done, so a recorded dream is never thrown away.
        onBackPressedDispatcher.addCallback(this) {
            if (recorder != null) {
                finishRecording()
                finish()
            }
        }

        ContextCompat.startForegroundService(this, RingtoneService.intent(this, alarm!!))
    }

    override fun onStop() {
        // Leaving the screen mid-recording (home button, incoming call) saves what
        // was recorded so far; the mic would be cut off in the background anyway.
        finishRecording()
        super.onStop()
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun startRecording() {
        if (alarm == null) return
        // Silence the alarm first. This also settles the alarm exactly as Dismiss
        // does, so the microphone never hears the ringtone and an interrupted
        // recording cannot leave the alarm schedule in a half-finished state.
        dismiss()
        val file = DreamDiary.newAudioFile(this)
        val newRecorder = DreamRecorder(file)
        if (!newRecorder.start()) {
            Toast.makeText(this, R.string.recording_failed, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        recorder = newRecorder
        audioFile = file
        recordedAt = System.currentTimeMillis()
        binding.status.visibility = View.VISIBLE
        binding.dismiss.visibility = View.GONE
        binding.record.setText(R.string.record_done)
    }

    private fun finishRecording() {
        val activeRecorder = recorder ?: return
        val file = audioFile ?: return
        recorder = null
        val durationMs = activeRecorder.stop()
        if (durationMs < MIN_DREAM_MS) {
            // Accidental double tap: nothing worth keeping.
            file.delete()
            return
        }
        DreamDiary.appendEntry(this, file, recordedAt, durationMs)
        Toast.makeText(this, R.string.dream_saved, Toast.LENGTH_SHORT).show()
    }

    // In-process direct call, not a broadcast: this activity is already in the
    // foreground, so round-tripping through the receiver queue would only add
    // latency (ringtone lingers) and a failure mode. Mirrors
    // AlarmReceiver.handleDismiss, which stays for the notification-action path
    // (PendingIntents must go through the receiver).
    private fun dismiss() {
        val alarm = alarm ?: return
        val scheduler = AlarmScheduler(this)
        // Re-read the store: a one-shot is already disabled there by the
        // receiver, and the stale in-memory copy must not re-arm it.
        val current = AlarmStore(this).get(alarm.id)
        // Dismiss ends this instance, not the series: cancel() below also
        // clears tomorrow's main (armed at fire time). Compute up front
        // whether there is a next instance so the DISMISSED row records it.
        val reArms = current != null && current.enabled && scheduler.nextTrigger(current) != null
        scheduler.cancel(alarm, "Dismiss")
        EventLog.log(this, EventType.DISMISSED, alarm.id, alarm.label, "Re-armed".takeIf { reArms })
        AlarmNotifier.stopRinging(this)
        // No-op for disabled/deleted one-shots (nextTrigger null).
        if (reArms) scheduler.schedule(current!!)
    }

    companion object {
        /** Recordings shorter than this are treated as an accidental tap and discarded. */
        private const val MIN_DREAM_MS = 1_000L

        fun intent(context: Context, alarmId: Long): Intent =
            Intent(context, AlarmActivity::class.java)
                .putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId)
    }
}
