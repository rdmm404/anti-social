package org.voltr.antisocial.tv

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackTest {
    private fun refused(code: Int) = HttpDataSource.InvalidResponseCodeException(
        code,
        null,
        null,
        emptyMap(),
        DataSpec.Builder().setUri(Uri.EMPTY).build(),
        ByteArray(0),
    )

    private fun retryDelay(error: IOException): Long =
        RefusalAwareErrorPolicy().getRetryDelayMsFor(
            LoadErrorHandlingPolicy.LoadErrorInfo(
                LoadEventInfo(0, DataSpec.Builder().setUri(Uri.EMPTY).build(), 0),
                MediaLoadData(C.DATA_TYPE_MEDIA),
                error,
                1,
            ),
        )

    @Test
    fun `a 402 anywhere in the cause chain is a refusal`() {
        assertTrue(refused(402).isRefused())
        assertTrue(RuntimeException("wrapped", refused(402)).isRefused())
    }

    @Test
    fun `other failures don't`() {
        assertFalse(refused(403).isRefused())
        assertFalse(IOException("network").isRefused())
    }

    @Test
    fun `gives up immediately on a 402 and still retries a network failure`() {
        assertEquals(C.TIME_UNSET, retryDelay(refused(402)))
        assertNotEquals(C.TIME_UNSET, retryDelay(IOException("network")))
    }
}
