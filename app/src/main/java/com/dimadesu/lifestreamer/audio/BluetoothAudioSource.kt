// BluetoothAudioSource implementation removed from app module.
// Implementing a StreamPack-compatible Bluetooth-backed source requires
// changes in the StreamPack core because many base classes and helpers are
// internal/sealed. Keep this file as a placeholder so app compiles while we
// rely on system routing (AudioManager/SCO) and the MicrophoneSourceFactory.

/*
If you want a proper Bluetooth-backed source that directly prefers an
AudioDeviceInfo when building AudioRecord, implement it inside the
StreamPack core module (e.g., `streampack-core`) so it can extend
`AudioRecordSource` and use the `AudioRecordSourceFactory` internal APIs.
*/

