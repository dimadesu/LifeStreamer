package com.dimadesu.lifestreamer.models

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