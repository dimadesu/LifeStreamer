# Mutex Lock Fix Documentation

## Problem Description

The app would sometimes get stuck and become unresponsive, particularly after killing the UI. Investigation revealed that mutex locks in `PreviewViewModel.kt` were not being released properly in certain scenarios.

## Root Cause

The `streamOperationMutex` is used in the `startStream()` and `stopStream()` methods to prevent race conditions during rapid start/stop operations. However, the implementation had a critical flaw:

```kotlin
// BEFORE (Incorrect)
viewModelScope.launch {
    streamOperationMutex.withLock {
        // ... some code ...
        if (errorCondition) {
            return@launch  // ❌ Exits the entire coroutine, mutex stays locked!
        }
        // ... more code ...
    }
}
```

The problem was that `return@launch` exits the **entire coroutine**, not just the `withLock` block. This means:
1. The lock is never released
2. Any subsequent calls to `startStream()` or `stopStream()` will hang indefinitely waiting for the lock
3. The app becomes unresponsive and requires killing the UI or app to recover

## Solution

The fix was to replace all `return@launch` statements inside `withLock` blocks with `return@withLock`:

```kotlin
// AFTER (Correct)
viewModelScope.launch {
    streamOperationMutex.withLock {
        try {
            // ... some code ...
            if (errorCondition) {
                return@withLock  // ✅ Exits only the lock block, mutex is released
            }
            // ... more code ...
        } finally {
            // Cleanup code that always runs
        }
    }
}
```

Additionally, proper try-finally blocks were added to ensure cleanup code runs even when exceptions occur.

## Changes Made

### In `startStream()` method (lines 996-1115):
- Changed 3 occurrences of `return@launch` to `return@withLock` (lines 1023, 1060, 1081)
- Added outer try-finally block to ensure lock release logging happens
- Ensured all early returns properly exit only the lock block

### In `stopStream()` method (lines 1206-1380):
- Changed 4 occurrences of `return@launch` to `return@withLock` (lines 1215, 1245, 1252, 1279)
- Added outer try-finally block to ensure lock release logging happens
- Ensured all early returns properly exit only the lock block

## Testing Scenarios

To verify the fix works correctly, test these scenarios:

1. **Normal start/stop**: Start and stop streaming multiple times - should work smoothly
2. **Rapid start/stop**: Quickly press start and stop buttons - should not hang
3. **Error during start**: Trigger an error during stream start (e.g., invalid endpoint) - app should not hang
4. **Error during stop**: Trigger an error during stream stop - app should not hang
5. **Kill UI during streaming**: Kill the UI while streaming is active, then restart - should be able to stop/start again
6. **Network disconnection**: Disconnect network during streaming - reconnection should work without hanging

## Technical Notes

### Why `withLock` is safe
The Kotlin coroutines `withLock` function is designed to properly handle:
- Normal completion: lock is released when the block ends
- Early returns: lock is released when `return@withLock` is executed
- Exceptions: lock is released when an exception is thrown
- Cancellation: lock is released when the coroutine is cancelled

### Cancellation safety
Even with this fix, there's a theoretical risk if the coroutine is cancelled exactly during lock acquisition. However:
- The `withLock` function is specifically designed to handle cancellation safely
- Kotlin coroutines guarantee structured concurrency
- The viewModelScope lifecycle ensures proper cleanup

### Why early returns were needed
The methods have multiple validation checks that need to exit early:
- Service not ready
- Streamer not initialized
- Sources not configured
- Already in desired state (streaming/stopped)

Using early returns keeps the code readable and avoids deeply nested if-else blocks.

## Prevention

To prevent this issue in the future:

1. **Always use `return@withLock`** when returning from inside a `withLock` block
2. **Never use `return@launch`** or `return@<outer-scope>` from inside a `withLock` block
3. **Use try-finally** blocks to ensure cleanup code runs
4. **Add logging** before and after lock acquisition to help diagnose lock issues
5. **Review all mutex usage** during code review to ensure proper lock release

## Related Issues

This fix addresses the issue: "After StreamPack library update sometimes app gets stuck and cannot stream. Usually after killing UI it gets unblocked."

The problem was not directly related to the StreamPack library update, but rather exposed an existing bug in the mutex lock handling that became more apparent after the update.
