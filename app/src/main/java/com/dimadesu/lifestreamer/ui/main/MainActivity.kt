/*
 * Copyright (C) 2021 Thibault B.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dimadesu.lifestreamer.ui.main

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuInflater
import android.widget.PopupMenu
import androidx.appcompat.app.AppCompatActivity
import com.dimadesu.lifestreamer.R
import com.dimadesu.lifestreamer.databinding.MainActivityBinding
import com.dimadesu.lifestreamer.ui.settings.SettingsActivity

class MainActivity : AppCompatActivity() {
    private lateinit var binding: MainActivityBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = MainActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.container, PreviewFragment())
                .commitNow()
        }

        bindProperties()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Handle notification tap action to avoid re-creating activity and
        // triggering unnecessary view detach/attach which can race with camera.
        val action = intent.action
        if (action == "com.dimadesu.lifestreamer.ACTION_OPEN_FROM_NOTIFICATION") {
            // If the PreviewFragment is already present, do nothing. If not,
            // ensure it's added without recreating the fragment stack.
            val current = supportFragmentManager.findFragmentById(R.id.container)
            if (current == null) {
                supportFragmentManager.beginTransaction()
                    .replace(R.id.container, PreviewFragment())
                    .commitNow()
            }
        } else if (action == "com.dimadesu.lifestreamer.action.EXIT_APP") {
            // Exit requested via notification: stop the service (if running) and finish activity
            try {
                val stopIntent = Intent(this, com.dimadesu.lifestreamer.services.CameraStreamerService::class.java)
                stopService(stopIntent)
            } catch (_: Exception) {}
            finish()
        }
    }

    private fun bindProperties() {
        binding.actions.setOnClickListener {
            showPopup()
        }
    }

    private fun showPopup() {
        val popup = PopupMenu(this, binding.actions)
        val inflater: MenuInflater = popup.menuInflater
        inflater.inflate(R.menu.actions, popup.menu)
        popup.show()
        popup.setOnMenuItemClickListener {
            if (it.itemId == R.id.action_settings) {
                goToSettingsActivity()
                true
            } else {
                Log.e(TAG, "Unknown menu item ${it.itemId}")
                false
            }
        }
    }

    private fun goToSettingsActivity() {
        val intent = Intent(this, SettingsActivity::class.java)
        startActivity(intent)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.actions, menu)
        return true
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
