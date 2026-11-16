package com.dimadesu.lifestreamer.rtmp

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.dimadesu.lifestreamer.ui.main.RtmpSourceSwitchHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sun.misc.Unsafe
import java.io.IOException
import java.net.SocketTimeoutException

@OptIn(UnstableApi::class)
class RtmpSourceSwitchHelperTest {

    private fun loadControlParameters(
        bufferedMs: Long,
        rebuffering: Boolean
    ): LoadControl.Parameters = LoadControl.Parameters(
        PlayerId.UNSET,
        Timeline.EMPTY,
        MediaSource.MediaPeriodId(Any()),
        0L,
        bufferedMs * 1_000L,
        1.0f,
        true,
        rebuffering,
        C.TIME_UNSET,
        0L
    )

    private fun prepareLoadControlWithTargetBuffer(loadControl: DefaultLoadControl) {
        loadControl.onPrepared(PlayerId.UNSET)
        val loadingStatesField = DefaultLoadControl::class.java.getDeclaredField("loadingStates")
        loadingStatesField.isAccessible = true
        val loadingStates = loadingStatesField.get(loadControl) as Map<*, *>
        val state = loadingStates[PlayerId.UNSET]!!
        val targetBufferBytesField = state.javaClass.getDeclaredField("targetBufferBytes")
        targetBufferBytesField.isAccessible = true
        targetBufferBytesField.setInt(state, DefaultLoadControl.DEFAULT_VIDEO_BUFFER_SIZE)
    }

    private fun dummyDataSpec(): DataSpec {
        val unsafeField = Unsafe::class.java.getDeclaredField("theUnsafe")
        unsafeField.isAccessible = true
        val unsafe = unsafeField.get(null) as Unsafe
        return unsafe.allocateInstance(DataSpec::class.java) as DataSpec
    }

    @Test
    fun createLoadControl_matchesBufferForPlaybackAndRebuffer() {
        val loadControl = RtmpSourceSwitchHelper.createLoadControl(2500)
        prepareLoadControlWithTargetBuffer(loadControl)

        assertFalse(loadControl.shouldStartPlayback(loadControlParameters(2499L, rebuffering = false)))
        assertTrue(loadControl.shouldStartPlayback(loadControlParameters(2500L, rebuffering = false)))

        // Rebuffer threshold matches user buffer (2500 ms) rather than forcing 5000 ms.
        assertFalse(loadControl.shouldStartPlayback(loadControlParameters(2499L, rebuffering = true)))
        assertTrue(loadControl.shouldStartPlayback(loadControlParameters(2500L, rebuffering = true)))
    }

    @Test
    fun createLoadControl_preservesLargerUserBufferOnRebuffer() {
        val loadControl = RtmpSourceSwitchHelper.createLoadControl(6000)
        prepareLoadControlWithTargetBuffer(loadControl)

        assertFalse(loadControl.shouldStartPlayback(loadControlParameters(5000L, rebuffering = true)))
        assertTrue(loadControl.shouldStartPlayback(loadControlParameters(6000L, rebuffering = true)))
    }

    @Test
    fun createLoadControl_clampsOutOfBoundsValuesSafely() {
        val negativeBufferControl = RtmpSourceSwitchHelper.createLoadControl(-500)
        prepareLoadControlWithTargetBuffer(negativeBufferControl)
        assertTrue(negativeBufferControl.shouldStartPlayback(loadControlParameters(0L, rebuffering = true)))

        val oversizedBufferControl = RtmpSourceSwitchHelper.createLoadControl(100_000)
        prepareLoadControlWithTargetBuffer(oversizedBufferControl)
        val minBufferMs = DefaultLoadControl.DEFAULT_MIN_BUFFER_MS.toLong()
        assertFalse(oversizedBufferControl.shouldStartPlayback(loadControlParameters(minBufferMs - 1L, rebuffering = true)))
        assertTrue(oversizedBufferControl.shouldStartPlayback(loadControlParameters(minBufferMs, rebuffering = true)))
    }

    @Test
    fun createFailFastLoadErrorHandlingPolicy_disablesInternalRetries() {
        val policy = RtmpSourceSwitchHelper.createFailFastLoadErrorHandlingPolicy()

        assertEquals(0, policy.getMinimumLoadableRetryCount(C.DATA_TYPE_MEDIA))
        assertEquals(0, policy.getMinimumLoadableRetryCount(C.DATA_TYPE_MEDIA_PROGRESSIVE_LIVE))

        val eventInfo = LoadEventInfo(1L, dummyDataSpec(), Uri.EMPTY, emptyMap(), 0L, 0L, 0L)
        val mediaLoadData = MediaLoadData(C.DATA_TYPE_MEDIA)

        assertEquals(
            C.TIME_UNSET,
            policy.getRetryDelayMsFor(
                LoadErrorHandlingPolicy.LoadErrorInfo(eventInfo, mediaLoadData, IOException("refused"), 1)
            )
        )
        assertEquals(
            C.TIME_UNSET,
            policy.getRetryDelayMsFor(
                LoadErrorHandlingPolicy.LoadErrorInfo(eventInfo, mediaLoadData, SocketTimeoutException("timeout"), 1)
            )
        )
    }
}
