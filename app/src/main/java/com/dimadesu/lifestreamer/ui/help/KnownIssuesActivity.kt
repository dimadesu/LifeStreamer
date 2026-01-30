package com.dimadesu.lifestreamer.ui.help

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.dimadesu.lifestreamer.databinding.ActivityKnownIssuesBinding

class KnownIssuesActivity : AppCompatActivity() {
    private lateinit var binding: ActivityKnownIssuesBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityKnownIssuesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Enable back button in action bar
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Known issues and workarounds"
    }

    override fun onSupportNavigateUp(): Boolean {
        @Suppress("DEPRECATION")
        onBackPressed()
        return true
    }
}
