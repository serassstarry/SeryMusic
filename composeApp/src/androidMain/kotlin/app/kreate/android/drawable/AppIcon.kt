package app.kreate.android.drawable

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.scale
import app.kreate.android.R
import app.kreate.android.drawable.AppIcon.Round.bitmap
import app.kreate.android.drawable.AppIcon.bitmap


/**
 * In-app icon (Sery with transparent background), used wherever
 * the app shows its own logo: header, widget, placeholders, etc.
 */
object AppIcon {

    private lateinit var _bitmap: Bitmap

    private fun logoBitmap( context: Context ): Bitmap {
        if( !::_bitmap.isInitialized )
            _bitmap = BitmapFactory.decodeResource( context.resources, R.drawable.sery_logo )

        return _bitmap
    }

    private fun logoBitmap( context: Context, size: Int ): Bitmap {
        val bitmap = logoBitmap( context )
        return if( bitmap.width == size && bitmap.height == size )
            bitmap
        else
            bitmap.scale( size, size )
    }

    /**
     * Decode [R.drawable.sery_logo] to [Bitmap].
     *
     * **This bitmap is immutable**
     *
     * This bitmap is sufficiently cached, thus, subsequent calls are
     * expected to be much faster.
     */
    fun bitmap( context: Context ): Bitmap = logoBitmap( context )

    /**
     * Same as [bitmap], resized to [size]x[size].
     *
     * **This bitmap is immutable**
     */
    fun bitmap( context: Context, size: Int ): Bitmap = logoBitmap( context, size )

    /**
     * Create [ImageBitmap] version of [bitmap].
     *
     * Just like [bitmap], this component is **immutable**
     */
    fun imageBitmap( context: Context ): ImageBitmap = bitmap( context ).asImageBitmap()

    /**
     * Kept for callers that ask for the round variant, the logo
     * has a transparent background so both variants are the same.
     */
    object Round {

        /**
         * Same as [AppIcon.bitmap].
         */
        fun bitmap( context: Context ): Bitmap = logoBitmap( context )

        /**
         * Same as [AppIcon.bitmap], resized to [size]x[size].
         */
        fun bitmap( context: Context, size: Int ): Bitmap = logoBitmap( context, size )

        /**
         * Create [ImageBitmap] version of [bitmap].
         *
         * Just like [bitmap], this component is **immutable**
         */
        fun imageBitmap( context: Context ): ImageBitmap = bitmap( context ).asImageBitmap()
    }
}
