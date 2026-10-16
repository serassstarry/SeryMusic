package app.kreate.di

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Test

class UpstreamLengthTest {

    @Test
    fun `keeps length of a bounded gap`() {
        // Gap between 2 cached parts must not be overrun
        assertEquals( 1_000L, upstreamLength( 5_000L, 1_000L, 10_000L ) )
    }

    @Test
    fun `requests the rest of the file when open ended`() {
        assertEquals( 10_000L, upstreamLength( 0L, C.LENGTH_UNSET.toLong(), 10_000L ) )
        assertEquals( 4_000L, upstreamLength( 6_000L, C.LENGTH_UNSET.toLong(), 10_000L ) )
    }

    @Test
    fun `stays unset when content length is unknown or exceeded`() {
        assertEquals( C.LENGTH_UNSET.toLong(), upstreamLength( 6_000L, C.LENGTH_UNSET.toLong(), null ) )
        assertEquals( C.LENGTH_UNSET.toLong(), upstreamLength( 10_000L, C.LENGTH_UNSET.toLong(), 10_000L ) )
    }
}
