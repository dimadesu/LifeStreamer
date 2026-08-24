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
import com.dimadesu.lifestreamer.rtmp.audio.MediaProjectionService
import com.dimadesu.lifestreamer.services.CameraStreamerService
import com.dimadesu.lifestreamer.ui.settings.SettingsActivity
import com.dimadesu.lifestreamer.ui.help.FaqHelpActivity
import com.dimadesu.lifestreamer.ui.help.KnownIssuesActivity
import com.dimadesu.lifestreamer.ui.help.RtmpHelpActivity
import com.dimadesu.lifestreamer.ui.help.SrtHelpActivity
import com.dimadesu.lifestreamer.ui.help.UvcHelpActivity

class MainActivity : AppCompatActivity() {
    private lateinit var binding: MainActivityBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Notification Quit starts a new activity when none is running.
        // Handle it here — onNewIntent is not called for a fresh launch —
        // and skip UI/service bind so we don't race teardown.
        if (intent?.action == CameraStreamerService.ACTION_EXIT_APP) {
            exitApp()
            return
        }

        binding = MainActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Note: Permissions (Camera, Microphone, Notifications) are requested by PreviewFragment.
        // Bluetooth permissions are requested on-demand when user taps the BT mic toggle.

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.container, PreviewFragment())
                .commitNow()
        }

        bindProperties()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Handle notification tap action to avoid re-creating activity and
        // triggering unnecessary view detach/attach which can race with camera.
        val action = intent.action
        if (action == CameraStreamerService.ACTION_EXIT_APP) {
            exitApp()
        } else if (action == CameraStreamerService.ACTION_OPEN_FROM_NOTIFICATION) {
            // If the PreviewFragment is already present, do nothing. If not,
            // ensure it's added without recreating the fragment stack.
            val current = supportFragmentManager.findFragmentById(R.id.container)
            if (current == null) {
                supportFragmentManager.beginTransaction()
                    .replace(R.id.container, PreviewFragment())
                    .commitNow()
            }
        }
    }

    private fun exitApp() {
        // Unbind first: a started+bound service ignores stopService() until
        // every client unbinds. PreviewViewModel otherwise unbinds only in
        // onCleared(), which runs after the activity is already finishing.
        (supportFragmentManager.findFragmentById(R.id.container) as? PreviewFragment)
            ?.prepareForExit()

        try {
            stopService(Intent(this, CameraStreamerService::class.java))
        } catch (_: Exception) {
            // Service might not be running, that's okay
        }
        try {
            stopService(Intent(this, MediaProjectionService::class.java))
        } catch (_: Exception) {
            // Optional companion service; ignore if absent
        }

        finishAndRemoveTask()
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
            when (it.itemId) {
                R.id.action_settings -> {
                    goToSettingsActivity()
                    true
                }
                R.id.action_rtmp_help -> {
                    goToRtmpHelpActivity()
                    true
                }
                R.id.action_srt_help -> {
                    goToSrtHelpActivity()
                    true
                }
                R.id.action_uvc_help -> {
                    goToUvcHelpActivity()
                    true
                }
                R.id.action_faq -> {
                    goToFaqHelpActivity()
                    true
                }
                R.id.action_known_issues -> {
                    goToKnownIssuesActivity()
                    true
                }
                R.id.action_quit -> {
                    exitApp()
                    true
                }
                else -> {
                    Log.e(TAG, "Unknown menu item ${it.itemId}")
                    false
                }
            }
        }
    }

    private fun goToSettingsActivity() {
        val intent = Intent(this, SettingsActivity::class.java)
        startActivity(intent)
    }

    private fun goToRtmpHelpActivity() {
        val intent = Intent(this, RtmpHelpActivity::class.java)
        startActivity(intent)
    }

    private fun goToSrtHelpActivity() {
        val intent = Intent(this, SrtHelpActivity::class.java)
        startActivity(intent)
    }

    private fun goToUvcHelpActivity() {
        val intent = Intent(this, UvcHelpActivity::class.java)
        startActivity(intent)
    }

    private fun goToFaqHelpActivity() {
        val intent = Intent(this, FaqHelpActivity::class.java)
        startActivity(intent)
    }

    private fun goToKnownIssuesActivity() {
        val intent = Intent(this, KnownIssuesActivity::class.java)
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
