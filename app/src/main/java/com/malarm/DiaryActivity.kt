package com.malarm

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.malarm.databinding.ActivityDiaryBinding

/** Read-only view of the dream diary, with a button to share it as text. */
class DiaryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDiaryBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDiaryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.share.setOnClickListener {
            val text = DreamDiary.read(this)
            if (text.isBlank()) return@setOnClickListener
            val send = Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, text)
            startActivity(Intent.createChooser(send, getString(R.string.share)))
        }
    }

    override fun onResume() {
        super.onResume()
        val text = DreamDiary.read(this)
        binding.diaryText.text = text.ifBlank { getString(R.string.diary_empty) }
        binding.scroll.post { binding.scroll.fullScroll(View.FOCUS_DOWN) }
    }
}
