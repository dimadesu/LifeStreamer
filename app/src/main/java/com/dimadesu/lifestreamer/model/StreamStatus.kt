package com.dimadesu.lifestreamer.model

/**
 * Shared streaming status enum used by both service and ViewModel to avoid duplication.
 */
enum class StreamStatus {
    NOT_STREAMING,
    STARTING,
    CONNECTING,
    STREAMING,
    ERROR
}
