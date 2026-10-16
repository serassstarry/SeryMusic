package app.kreate.android.service.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import app.kreate.di.CachedFormatMismatchException
import java.io.IOException


@UnstableApi
class ErrorHandlingPolicy : DefaultLoadErrorHandlingPolicy(){

    // TODO: Inspect error to determine eligibility
    override fun isEligibleForFallback( exception: IOException ): Boolean = true

    override fun getRetryDelayMsFor( loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo ): Long =
        // Retrying hits the same cached data again, fail right away
        // so [ExoPlayerListener] can clear the cache and restart
        if( loadErrorInfo.exception is CachedFormatMismatchException )
            C.TIME_UNSET
        else
            super.getRetryDelayMsFor( loadErrorInfo )
}
