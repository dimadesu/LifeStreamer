package com.dimadesu.lifestreamer.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.util.Log
import io.github.thibaultbee.streampack.core.elements.sources.audio.audiorecord.MicrophoneSourceFactory
import io.github.thibaultbee.streampack.core.interfaces.IWithAudioSource
import io.github.thibaultbee.streampack.core.streamers.single.ISingleStreamer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Manages Bluetooth audio routing and SCO (Synchronous Connection Oriented) link negotiation
 * for streaming and audio passthrough.
 * 
 * This class encapsulates all Bluetooth HFP/SCO orchestration logic including:
 * - Device detection and monitoring
 * - SCO negotiation and state management
 * - Device connect/disconnect handling
 * - Audio source switching between built-in mic and Bluetooth
 * 
 * @param context Application context
 * @param scope CoroutineScope for background operations
 * @param bluetoothConnectPermissionRequest Flow to request BLUETOOTH_CONNECT permission from UI
 */
class BluetoothAudioManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val bluetoothConnectPermissionRequest: MutableSharedFlow<Unit>
) {
    private val scoOrchestrator = ScoOrchestrator(context, scope, bluetoothConnectPermissionRequest)
    
    private val scoMutex = Mutex()
    private var scoSwitchJob: Job? = null
    private var scoDisconnectReceiver: BroadcastReceiver? = null
    private var btDeviceReceiver: BroadcastReceiver? = null
    private var btDeviceMonitorJob: Job? = null
    
    /**
     * SCO negotiation state exposed to UI
     */
    enum class ScoState { IDLE, TRYING, USING_BT, FAILED }
    
    private val _scoStateFlow = MutableStateFlow(ScoState.IDLE)
    val scoStateFlow: StateFlow<ScoState> = _scoStateFlow.asStateFlow()
    
    /**
     * Tracks if SCO was started specifically for passthrough monitoring
     */
    var scoStartedForPassthrough: Boolean = false
        private set

    /**
     * Apply Bluetooth mic policy at runtime.
     * When enabled: Starts monitoring for BT devices and attempts SCO negotiation if streaming.
     * When disabled: Stops SCO orchestration and reverts to built-in mic.
     * 
     * @param enabled Whether to enable Bluetooth mic preference
     * @param streamer Optional streamer instance for audio source switching
     * @param isStreaming Whether currently streaming
     */
    fun applyPolicy(enabled: Boolean, streamer: ISingleStreamer?, isStreaming: Boolean) {
        BluetoothAudioConfig.setEnabled(enabled)

        if (!enabled) {
            // Cancel SCO orchestration and cleanup
            scoSwitchJob?.cancel()
            stopScoAndResetAudio()
            BluetoothAudioConfig.setPreferredDevice(null)
            
            // Recreate microphone source to switch back to built-in mic
            recreateMicSource(streamer)
            
            _scoStateFlow.tryEmit(ScoState.IDLE)
            unregisterBtDeviceReceiver()
        } else {
            // Ensure permission before attempting any BT operations
            if (!scoOrchestrator.ensurePermission()) {
                Log.i(TAG, "applyPolicy: BLUETOOTH_CONNECT not granted - requesting UI and reverting toggle")
                BluetoothAudioConfig.setEnabled(false)
                _scoStateFlow.tryEmit(ScoState.FAILED)
                return
            }

            // If streaming, attempt SCO orchestration
            if (streamer != null && isStreaming) {
                scoSwitchJob?.cancel()
                scoSwitchJob = scope.launch(Dispatchers.Default) {
                    delay(200)
                    attemptScoNegotiationAndSwitch(streamer)
                }
            } else {
                // Quick connectivity check for immediate UI feedback
                performConnectivityCheck()
            }
            
            // Register device monitoring
            registerBtDeviceReceiver()
        }
    }

    /**
     * Performs a quick connectivity check to verify BT device availability.
     * Updates state to USING_BT if successful, FAILED otherwise.
     */
    private fun performConnectivityCheck() {
        scope.launch(Dispatchers.Default) {
            try {
                _scoStateFlow.tryEmit(ScoState.TRYING)
                
                val btDevice = scoOrchestrator.detectBtInputDevice()
                if (btDevice == null) {
                    Log.i(TAG, "Connectivity check: no BT input device detected")
                    revertPolicy()
                    return@launch
                }

                BluetoothAudioConfig.setPreferredDevice(btDevice)
                
                if (!scoOrchestrator.ensurePermission()) {
                    Log.i(TAG, "Connectivity check: permission missing")
                    revertPolicy()
                    return@launch
                }

                val connected = scoOrchestrator.startScoAndWait(3000)
                if (!connected) {
                    Log.i(TAG, "Connectivity check: SCO did not connect")
                    scoOrchestrator.stopScoQuietly()
                    revertPolicy()
                    return@launch
                }

                // Success - stop SCO and verify routing
                scoOrchestrator.stopScoQuietly()
                verifyAndEmitUsingBtOrFail(1500)
            } catch (t: Throwable) {
                Log.w(TAG, "Connectivity check failed: ${t.message}")
                revertPolicy()
            }
        }
    }

    /**
     * Attempt SCO negotiation and switch streamer's audio source to Bluetooth.
     * This is the main orchestration flow for streaming scenarios.
     */
    suspend fun attemptScoNegotiationAndSwitch(streamerInstance: ISingleStreamer?) {
        if (streamerInstance == null || !BluetoothAudioConfig.isEnabled()) {
            Log.i(TAG, "SCO orchestration skipped: streamer=$streamerInstance enabled=${BluetoothAudioConfig.isEnabled()}")
            return
        }

        // Detect or use cached preferred device
        var preferred = BluetoothAudioConfig.getPreferredDevice()
        if (preferred == null) {
            val btDevice = scoOrchestrator.detectBtInputDevice()
            if (btDevice != null) {
                BluetoothAudioConfig.setPreferredDevice(btDevice)
                preferred = btDevice
                Log.i(TAG, "SCO orchestration: detected BT input device id=${btDevice.id}")
            } else {
                Log.i(TAG, "SCO orchestration: no BT input device detected")
            }
        }

        scoMutex.withLock {
            _scoStateFlow.tryEmit(ScoState.TRYING)
            
            // Check if already using Bluetooth source
            val audioInput = (streamerInstance as? IWithAudioSource)?.audioInput
            val currentSource = audioInput?.sourceFlow?.value
            if (currentSource != null && currentSource.javaClass.simpleName.contains("Bluetooth", ignoreCase = true)) {
                Log.i(TAG, "SCO orchestration: audio source already Bluetooth - verifying")
                verifyAndEmitUsingBtOrFail(1200)
                return
            }

            Log.i(TAG, "SCO orchestration: starting negotiation for device id=${preferred?.id ?: -1}")

            if (!scoOrchestrator.ensurePermission()) {
                Log.w(TAG, "SCO orchestration: permission missing")
                _scoStateFlow.tryEmit(ScoState.FAILED)
                return
            }

            val connected = scoOrchestrator.startScoAndWait(4000)
            if (!connected) {
                Log.i(TAG, "SCO orchestration: SCO did not connect")
                scoOrchestrator.stopScoQuietly()
                recreateMicSource(streamerInstance)
                _scoStateFlow.tryEmit(ScoState.FAILED)
                return
            }

            Log.i(TAG, "SCO orchestration: SCO connected - switching audio source to Bluetooth")

            // Switch to Bluetooth source
            try {
                (streamerInstance as? IWithAudioSource)?.setAudioSource(
                    AppBluetoothSourceFactory(preferred)
                )
                Log.i(TAG, "SCO orchestration: setAudioSource called for Bluetooth factory")
            } catch (t: Throwable) {
                Log.w(TAG, "SCO orchestration: setAudioSource failed: ${t.message}")
                _scoStateFlow.tryEmit(ScoState.FAILED)
                return
            }

            // Register disconnect monitoring and verify routing
            registerScoDisconnectReceiver(streamerInstance)
            verifyAndEmitUsingBtOrFail(2000)
        }
    }

    /**
     * Start SCO for passthrough/monitoring use case.
     * @return true if SCO was started successfully
     */
    suspend fun startScoForPassthrough(): Boolean {
        if (!BluetoothAudioConfig.isEnabled()) return false
        
        _scoStateFlow.tryEmit(ScoState.TRYING)
        
        val btDevice = scoOrchestrator.detectBtInputDevice()
        if (btDevice == null) {
            Log.i(TAG, "Passthrough SCO: no BT input device detected")
            BluetoothAudioConfig.setPreferredDevice(null)
            _scoStateFlow.tryEmit(ScoState.FAILED)
            return false
        }

        BluetoothAudioConfig.setPreferredDevice(btDevice)
        
        if (!scoOrchestrator.ensurePermission()) {
            Log.w(TAG, "Passthrough SCO: permission missing")
            BluetoothAudioConfig.setPreferredDevice(null)
            _scoStateFlow.tryEmit(ScoState.FAILED)
            return false
        }

        val connected = scoOrchestrator.startScoAndWait(4000)
        if (!connected) {
            Log.i(TAG, "Passthrough SCO: SCO did not connect")
            scoOrchestrator.stopScoQuietly()
            BluetoothAudioConfig.setPreferredDevice(null)
            _scoStateFlow.tryEmit(ScoState.FAILED)
            return false
        }

        scoStartedForPassthrough = true
        Log.i(TAG, "Passthrough SCO: connected successfully")
        
        // Set audio mode for better routing
        setAudioMode(AudioManager.MODE_IN_COMMUNICATION)
        
        // Verify routing
        verifyAndEmitUsingBtOrFail(2000)
        return true
    }

    /**
     * Stop SCO that was started for passthrough.
     */
    fun stopScoForPassthrough() {
        if (scoStartedForPassthrough) {
            stopScoAndResetAudio()
            scoStartedForPassthrough = false
            _scoStateFlow.tryEmit(ScoState.IDLE)
            Log.i(TAG, "Stopped SCO that was started for passthrough")
        }
    }

    /**
     * Verify actual platform routing before emitting USING_BT state.
     * Polls AudioManager.isBluetoothScoOn and device detection.
     * 
     * @param timeoutMs Maximum time to wait for verification
     * @param pollMs Polling interval
     * @return true if verification succeeded and USING_BT emitted, false if FAILED emitted
     */
    private suspend fun verifyAndEmitUsingBtOrFail(timeoutMs: Long = 2000, pollMs: Long = 250): Boolean {
        val am = getAudioManager()
        val deadline = System.currentTimeMillis() + timeoutMs
        var attempt = 0
        
        while (System.currentTimeMillis() < deadline) {
            attempt++
            val isScoOn = try { am?.isBluetoothScoOn ?: false } catch (_: Throwable) { false }
            val btDevice = try { scoOrchestrator.detectBtInputDevice() } catch (_: Throwable) { null }
            
            Log.d(TAG, "verifyAndEmitUsingBtOrFail: attempt=$attempt isBluetoothScoOn=$isScoOn btDeviceId=${btDevice?.id ?: -1}")
            
            if (isScoOn || btDevice != null) {
                _scoStateFlow.tryEmit(ScoState.USING_BT)
                Log.i(TAG, "verifyAndEmitUsingBtOrFail: confirmed SCO routing - emitted USING_BT")
                return true
            }
            delay(pollMs)
        }
        
        Log.i(TAG, "verifyAndEmitUsingBtOrFail: failed to confirm SCO routing after ${timeoutMs}ms - emitting FAILED")
        _scoStateFlow.tryEmit(ScoState.FAILED)
        return false
    }

    /**
     * Register receiver to monitor SCO disconnection during streaming.
     */
    private fun registerScoDisconnectReceiver(streamerInstance: ISingleStreamer?) {
        if (scoDisconnectReceiver != null) return
        
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                intent?.let { i ->
                    if (i.action == AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED) {
                        val state = i.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
                        if (state == AudioManager.SCO_AUDIO_STATE_DISCONNECTED) {
                            Log.i(TAG, "SCO disconnect detected - reverting to microphone source")
                            handleScoDisconnect(streamerInstance)
                        }
                    }
                }
            }
        }
        
        val filter = IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
        context.registerReceiver(receiver, filter)
        scoDisconnectReceiver = receiver
    }

    /**
     * Handle SCO disconnection by reverting to built-in mic.
     */
    private fun handleScoDisconnect(streamerInstance: ISingleStreamer?) {
        scope.launch(Dispatchers.Default) {
            try {
                stopScoAndResetAudio()
                delay(250)
                recreateMicSource(streamerInstance)
                delay(150)
                _scoStateFlow.tryEmit(ScoState.IDLE)
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to revert to mic on SCO disconnect: ${t.message}")
            }
        }
    }

    /**
     * Register receiver to monitor Bluetooth device connect/disconnect events.
     */
    private fun registerBtDeviceReceiver() {
        if (btDeviceReceiver != null) return
        
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val action = intent?.action ?: return
                Log.d(TAG, "BT device receiver action=$action")
                
                if (action == android.bluetooth.BluetoothDevice.ACTION_ACL_DISCONNECTED ||
                    action == android.bluetooth.BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED) {
                    handleDeviceDisconnect()
                }
            }
        }
        
        val filter = IntentFilter().apply {
            addAction(android.bluetooth.BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(android.bluetooth.BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED)
        }
        
        context.registerReceiver(receiver, filter)
        btDeviceReceiver = receiver
        
        // Start polling monitor as fallback
        startDeviceMonitor()
    }

    /**
     * Handle Bluetooth device disconnection.
     */
    private fun handleDeviceDisconnect() {
        scope.launch(Dispatchers.Default) {
            try {
                var btDevice = scoOrchestrator.detectBtInputDevice()
                if (btDevice == null) {
                    val headsetConnected = scoOrchestrator.isHeadsetConnected()
                    if (!headsetConnected) btDevice = null
                }
                
                Log.d(TAG, "BT device disconnect: detected btDevice=${btDevice?.id ?: -1}")
                
                if (btDevice == null) {
                    Log.i(TAG, "BT device disconnected and no BT input remains - reverting policy")
                    revertPolicy()
                    
                    if (scoStartedForPassthrough) {
                        stopScoForPassthrough()
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "BT device receiver handling failed: ${t.message}")
            }
        }
    }

    /**
     * Start background job to poll for device presence (fallback for unreliable broadcasts).
     */
    private fun startDeviceMonitor() {
        btDeviceMonitorJob?.cancel()
        btDeviceMonitorJob = scope.launch(Dispatchers.Default) {
            var consecutiveMisses = 0
            while (true) {
                try {
                    val btDevice = scoOrchestrator.detectBtInputDevice()
                    val headsetConnected = if (btDevice == null) scoOrchestrator.isHeadsetConnected() else true
                    
                    if (btDevice != null || headsetConnected) {
                        consecutiveMisses = 0
                    } else {
                        consecutiveMisses++
                        Log.d(TAG, "BT monitor: no device found (misses=$consecutiveMisses)")
                    }
                    
                    if (consecutiveMisses >= 2) {
                        Log.i(TAG, "BT monitor: device missing for multiple checks - reverting policy")
                        revertPolicy()
                        if (scoStartedForPassthrough) {
                            stopScoForPassthrough()
                        }
                        break
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "BT monitor error: ${t.message}")
                }
                delay(2000)
            }
        }
    }

    /**
     * Unregister device monitoring receivers.
     */
    private fun unregisterBtDeviceReceiver() {
        try {
            btDeviceReceiver?.let { context.unregisterReceiver(it) }
        } catch (_: Throwable) {}
        btDeviceReceiver = null
        
        btDeviceMonitorJob?.cancel()
        btDeviceMonitorJob = null
    }

    /**
     * Unregister SCO disconnect receiver.
     */
    private fun unregisterScoDisconnectReceiver() {
        try {
            scoDisconnectReceiver?.let { context.unregisterReceiver(it) }
        } catch (_: Throwable) {}
        scoDisconnectReceiver = null
    }

    /**
     * Cleanup all Bluetooth resources and receivers.
     */
    fun cleanup() {
        scoSwitchJob?.cancel()
        unregisterScoDisconnectReceiver()
        unregisterBtDeviceReceiver()
        stopScoAndResetAudio()
    }

    /**
     * Revert Bluetooth policy (disable and emit FAILED).
     */
    private fun revertPolicy() {
        BluetoothAudioConfig.setEnabled(false)
        BluetoothAudioConfig.setPreferredDevice(null)
        _scoStateFlow.tryEmit(ScoState.FAILED)
    }

    /**
     * Stop SCO and restore normal audio routing.
     */
    private fun stopScoAndResetAudio() {
        val am = getAudioManager()
        try { am?.stopBluetoothSco() } catch (_: Throwable) {}
        setAudioMode(AudioManager.MODE_NORMAL)
    }

    /**
     * Set AudioManager mode.
     */
    private fun setAudioMode(mode: Int) {
        try {
            getAudioManager()?.mode = mode
        } catch (_: Throwable) {}
    }

    /**
     * Get AudioManager instance.
     */
    private fun getAudioManager(): AudioManager? {
        return try {
            context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Recreate microphone audio source to ensure clean session.
     */
    private fun recreateMicSource(streamerInstance: ISingleStreamer?) {
        try {
            Log.i(TAG, "Recreating mic source to ensure clean audio session")
            scope.launch(Dispatchers.Default) {
                try {
                    (streamerInstance as? IWithAudioSource)?.setAudioSource(
                        MicrophoneSourceFactory()
                    )
                    Log.i(TAG, "Recreate mic source: used MicrophoneSourceFactory")
                } catch (t: Throwable) {
                    Log.w(TAG, "Recreate mic source failed: ${t.message}")
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Recreate mic source outer failed: ${t.message}")
        }
    }

    companion object {
        private const val TAG = "BluetoothAudioManager"
    }
}
