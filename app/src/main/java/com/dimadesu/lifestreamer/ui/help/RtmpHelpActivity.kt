package com.dimadesu.lifestreamer.ui.help

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.dimadesu.lifestreamer.databinding.ActivityRtmpHelpBinding

class RtmpHelpActivity : AppCompatActivity() {
    private lateinit var binding: ActivityRtmpHelpBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRtmpHelpBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Enable back button in action bar
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "RTMP Source Help"
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }
}
