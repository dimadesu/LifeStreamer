package com.dimadesu.lifestreamer.ui.help

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.dimadesu.lifestreamer.databinding.ActivityUvcHelpBinding

class UvcHelpActivity : AppCompatActivity() {
    private lateinit var binding: ActivityUvcHelpBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUvcHelpBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Enable back button in action bar
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "USB Source Help"
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }
}
