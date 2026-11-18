package app.kreate.android.lockscreen

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import app.kreate.android.Preferences

/**
 * Opens [LockScreenActivity] when the screen turns off while music plays,
 * if the "player on the lock screen" setting is on.
 *
 * Android 10+ only lets an app open a screen from the background with the
 * "display over other apps" permission, see [hasPermission].
 */
class LockScreenLauncher( private val context: Context, private val player: Player ) {

    companion object {

        fun hasPermission( context: Context ): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays( context )

        /** Settings page where the user grants "display over other apps" */
        fun permissionIntent( context: Context ): Intent =
            Intent( Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}") )
                .addFlags( Intent.FLAG_ACTIVITY_NEW_TASK )
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive( context: Context, intent: Intent ) {
            if( intent.action != Intent.ACTION_SCREEN_OFF ) return
            if( !Preferences.LOCKSCREEN_PLAYER.value || !player.isPlaying || !hasPermission(context) ) return

            runCatching {
                context.startActivity(
                    Intent( context, LockScreenActivity::class.java )
                        .addFlags( Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION )
                )
            }
        }
    }

    fun register() {
        // Screen off is a protected system broadcast, it arrives even when not exported
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter( Intent.ACTION_SCREEN_OFF ), ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    fun unregister() {
        runCatching { context.unregisterReceiver( receiver ) }
    }
}
