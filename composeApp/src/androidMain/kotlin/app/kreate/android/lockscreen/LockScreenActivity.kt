@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.kreate.android.lockscreen

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import app.kreate.android.R
import app.kreate.android.service.player.SpectrumAnalyzer
import app.kreate.android.service.player.SpectrumTap
import app.kreate.android.service.player.StatefulPlayer
import app.kreate.android.tv.Background
import app.kreate.android.tv.Cover
import app.kreate.android.tv.DefaultCoverColors
import app.kreate.android.tv.TvButton
import app.kreate.android.tv.Visualizer
import app.kreate.android.tv.coverColors
import app.kreate.android.tv.formatTime
import it.fast4x.rimusic.Database
import it.fast4x.rimusic.utils.DisposableListener
import it.fast4x.rimusic.utils.positionAndDurationState
import it.fast4x.rimusic.utils.shouldBePlaying
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import me.knighthat.sync.YouTubeSync
import org.koin.compose.koinInject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Full screen player shown over the lock screen, see [LockScreenLauncher].
 *
 * Closes itself when the phone is unlocked, when "Unlock" is pressed
 * (which asks for the PIN/fingerprint) or with Back.
 */
class LockScreenActivity : ComponentActivity() {

    private val unlockedReceiver = object : BroadcastReceiver() {
        override fun onReceive( context: Context, intent: Intent ) = finish()
    }

    override fun onCreate( savedInstanceState: Bundle? ) {
        super.onCreate( savedInstanceState )

        if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 )
            setShowWhenLocked( true )
        else
            @Suppress("DEPRECATION")
            window.addFlags( WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED )

        ContextCompat.registerReceiver(
            this, unlockedReceiver, IntentFilter( Intent.ACTION_USER_PRESENT ), ContextCompat.RECEIVER_NOT_EXPORTED
        )

        setContent {
            LockScreenPlayer(
                onClose = ::finish,
                onUnlock = ::unlock
            )
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver( unlockedReceiver ) }
        super.onDestroy()
    }

    private fun unlock() {
        val keyguard = getSystemService( KeyguardManager::class.java )
        if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && keyguard?.isKeyguardLocked == true )
            keyguard.requestDismissKeyguard( this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() = finish()
            } )
        else
            finish()
    }
}

@Composable
private fun LockScreenPlayer( onClose: () -> Unit, onUnlock: () -> Unit ) {
    val player: StatefulPlayer = koinInject()
    val context = LocalContext.current
    val mediaItem by player.currentMediaItemState.collectAsState()
    val item = mediaItem ?: run {
        LaunchedEffect( Unit ) { onClose() }
        return
    }

    var isPlaying by remember { mutableStateOf( player.shouldBePlaying ) }
    player.DisposableListener {
        object : Player.Listener {
            override fun onPlayWhenReadyChanged( playWhenReady: Boolean, reason: Int ) { isPlaying = player.shouldBePlaying }
            override fun onPlaybackStateChanged( playbackState: Int ) { isPlaying = player.shouldBePlaying }
        }
    }
    val positionAndDuration by player.positionAndDurationState()
    val isLiked by remember( item.mediaId ) {
        Database.songTable.isLiked( item.mediaId ).distinctUntilChanged()
    }.collectAsState( false, Dispatchers.IO )

    val artwork = item.mediaMetadata.artworkUri?.toString()
    val colors by produceState( DefaultCoverColors, artwork ) {
        value = artwork?.let { coverColors( it ) } ?: DefaultCoverColors
    }
    val main by animateColorAsState( colors.main, tween(1200), label = "main" )
    val second by animateColorAsState( colors.second, tween(1200), label = "second" )
    val dark by animateColorAsState( colors.dark, tween(1200), label = "dark" )

    // Spectrum of what's playing, about 30 times per second
    val analyzer = remember { SpectrumAnalyzer( 36 ) }
    var frame by remember { mutableLongStateOf( 0L ) }
    var bass by remember { mutableFloatStateOf( 0f ) }
    DisposableEffect( Unit ) {
        SpectrumTap.acquire()
        onDispose { SpectrumTap.release() }
    }
    LaunchedEffect( Unit ) {
        var last = 0L
        while( true ) withFrameMillis { now ->
            if( now - last >= 33 ) {
                last = now
                analyzer.update()
                bass = analyzer.bass
                frame = now
            }
        }
    }

    BackHandler( onBack = onClose )

    Box( Modifier.fillMaxSize().background(Color.Black) ) {
        Background( artwork, main, second, dark, isPlaying, { bass } )

        Visualizer(
            frame = { frame },
            levels = analyzer,
            from = main,
            to = second,
            modifier = Modifier.align( Alignment.BottomCenter ).fillMaxWidth().height( 150.dp )
        )

        BoxWithConstraints( Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 28.dp) ) {
            val coverSize = minOf( maxWidth * 0.74f, maxHeight * 0.36f )

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxSize()
            ) {
                Spacer( Modifier.height(28.dp) )
                BigClock()
                Spacer( Modifier.weight(1f) )

                Cover( artwork, coverSize, main, second, isPlaying, { bass } )
                Spacer( Modifier.height(34.dp) )

                Text(
                    text = item.mediaMetadata.title?.toString().orEmpty(),
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.ExtraBold,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = item.mediaMetadata.artist?.toString().orEmpty(),
                    color = Color.White.copy( alpha = 0.72f ),
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding( top = 4.dp )
                )

                Spacer( Modifier.height(18.dp) )
                ProgressLine( positionAndDuration.first, positionAndDuration.second, main )

                Spacer( Modifier.height(22.dp) )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy( 16.dp )
                ) {
                    TvButton(
                        icon = if( isLiked ) R.drawable.heart else R.drawable.heart_outline,
                        description = "Like",
                        accent = main,
                        size = 48.dp,
                        active = isLiked
                    ) {
                        CoroutineScope( Dispatchers.IO ).launch {
                            YouTubeSync.toggleSongLike( context, item )
                        }
                    }
                    TvButton( R.drawable.play_skip_back, "Previous", main ) { player.seekToPrevious() }
                    TvButton(
                        icon = if( isPlaying ) R.drawable.pause else R.drawable.play,
                        description = if( isPlaying ) "Pause" else "Play",
                        accent = main,
                        size = 76.dp,
                        primary = true
                    ) {
                        if( player.shouldBePlaying ) player.pause()
                        else {
                            if( player.playbackState == Player.STATE_IDLE ) player.prepare()
                            player.play()
                        }
                    }
                    TvButton( R.drawable.play_skip_forward, "Next", main ) { player.seekToNext() }
                    Spacer( Modifier.size(48.dp) )
                }

                Spacer( Modifier.weight(1f) )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip( RoundedCornerShape(50) )
                        .background( Color.White.copy(alpha = 0.14f) )
                        .clickable( onClick = onUnlock )
                        .padding( horizontal = 22.dp, vertical = 12.dp )
                ) {
                    Icon( painterResource(R.drawable.unlocked), null, tint = Color.White, modifier = Modifier.size(18.dp) )
                    Spacer( Modifier.size(8.dp) )
                    Text( stringResource(R.string.lockscreen_unlock), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold )
                }
                Spacer( Modifier.height(170.dp) )
            }
        }
    }
}

@Composable
private fun BigClock() {
    val now by produceState( Date() ) {
        while( true ) {
            value = Date()
            delay( 1000 )
        }
    }
    val time = remember( now ) { SimpleDateFormat( "HH:mm", Locale.getDefault() ).format( now ) }
    val date = remember( now ) { SimpleDateFormat( "EEEE d MMMM", Locale.getDefault() ).format( now ) }
    Text( time, color = Color.White, fontSize = 64.sp, fontWeight = FontWeight.Light )
    Text( date.replaceFirstChar { it.uppercase() }, color = Color.White.copy(alpha = 0.75f), fontSize = 16.sp )
}

@Composable
private fun ProgressLine( position: Long, duration: Long, accent: Color ) {
    val fraction = if( duration > 0 ) (position.toFloat() / duration).coerceIn( 0f, 1f ) else 0f
    Column( Modifier.fillMaxWidth() ) {
        Box( Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.18f)) ) {
            Box( Modifier.fillMaxWidth(fraction).height(4.dp).background(accent) )
        }
        Row( Modifier.fillMaxWidth().padding(top = 6.dp) ) {
            Text( formatTime(position), color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp )
            Spacer( Modifier.weight(1f) )
            Text( formatTime(duration), color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp )
        }
    }
}
