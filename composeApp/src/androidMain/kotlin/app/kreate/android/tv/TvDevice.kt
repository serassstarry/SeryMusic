package app.kreate.android.tv

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration

private var cachedIsTv: Boolean? = null

/**
 * True on Android TV, Google TV and Fire TV: devices driven by a remote,
 * where the app shows a focus highlight and the full screen TV player.
 */
fun isTvDevice( context: Context ): Boolean = cachedIsTv ?: run {
    val pm = context.packageManager
    val uiMode = context.getSystemService( Context.UI_MODE_SERVICE ) as? UiModeManager

    val isTv = uiMode?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
            || pm.hasSystemFeature( PackageManager.FEATURE_LEANBACK )
            || pm.hasSystemFeature( "amazon.hardware.fire_tv" )
    cachedIsTv = isTv
    isTv
}
