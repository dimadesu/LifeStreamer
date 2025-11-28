package com.dimadesu.lifestreamer.ui.help

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.dimadesu.lifestreamer.databinding.ActivitySrtHelpBinding

class SrtHelpActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySrtHelpBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySrtHelpBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Enable back button in action bar
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "SRT Source Help"
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }
}
