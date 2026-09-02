package com.dimadesu.lifestreamer.bitrate

enum class RtmpRegulatorMode {
    SEND_DURATION,  // LS custom: RtmpSendDurationBitrateRegulator (fill-ratio based)
    SP_SIMPLE       // StreamPack default: SimpleBitrateRegulator (packet-loss based)
}
