@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.kreate.android.tv

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.media3.common.Timeline
import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.palette.graphics.Palette
import app.kreate.android.R
import app.kreate.android.coil3.BlurTransformation
import app.kreate.android.coil3.ImageFactory
import app.kreate.android.service.player.SpectrumAnalyzer
import app.kreate.android.service.player.SpectrumTap
import app.kreate.android.service.player.StatefulPlayer
import coil3.request.allowHardware
import it.fast4x.rimusic.Database
import it.fast4x.rimusic.utils.DisposableListener
import it.fast4x.rimusic.utils.positionAndDurationState
import it.fast4x.rimusic.utils.shouldBePlaying
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.knighthat.sync.YouTubeSync
import org.koin.compose.koinInject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.sin

private const val BANDS = 48
private const val SEEK_STEP_MS = 10_000L
/** Without touching the remote for this long, controls hide and only the art and effects stay */
private const val AMBIENT_AFTER_MS = 12_000L

/** Colors taken from the cover, used by the background, glow and visualizer */
private data class CoverColors( val main: Color, val second: Color, val dark: Color )

private val DefaultCoverColors = CoverColors( Color(0xFF8B5CF6), Color(0xFFEC4899), Color(0xFF1E1033) )

/**
 * Full screen player for TVs, made for the remote: big cover over its own
 * blurred image, light that pulses with the music, a real spectrum and large
 * controls. Arrow keys move between controls, the progress bar seeks with
 * left/right, and fast forward/rewind on the remote jump 10 seconds.
 *
 * [onMore] opens the song's full menu (playlists, radio, download...).
 */
@Composable
fun TvPlayer(
    onMore: (MediaItem) -> Unit,
    onClose: () -> Unit
) {
    val player: StatefulPlayer = koinInject()
    val mediaItem by player.currentMediaItemState.collectAsState()
    val item = mediaItem ?: run {
        LaunchedEffect( Unit ) { onClose() }
        return
    }

    var isPlaying by remember { mutableStateOf( player.shouldBePlaying ) }
    var repeatMode by remember { mutableIntStateOf( player.repeatMode ) }
    var shuffle by remember { mutableStateOf( player.shuffleModeEnabled ) }
    var currentIndex by remember { mutableIntStateOf( player.currentMediaItemIndex ) }
    var queueOpen by remember { mutableStateOf( false ) }
    player.DisposableListener {
        object : Player.Listener {
            override fun onPlayWhenReadyChanged( playWhenReady: Boolean, reason: Int ) { isPlaying = player.shouldBePlaying }
            override fun onPlaybackStateChanged( playbackState: Int ) { isPlaying = player.shouldBePlaying }
            override fun onRepeatModeChanged( mode: Int ) { repeatMode = mode }
            override fun onShuffleModeEnabledChanged( enabled: Boolean ) { shuffle = enabled }
            override fun onMediaItemTransition( mediaItem: MediaItem?, reason: Int ) { currentIndex = player.currentMediaItemIndex }
        }
    }
    val positionAndDuration by player.positionAndDurationState()
    val isLiked by remember( item.mediaId ) {
        Database.songTable.isLiked( item.mediaId ).distinctUntilChanged()
    }.collectAsState( false, Dispatchers.IO )
    val upNext = remember( item, player.currentMediaItemIndex ) {
        player.nextMediaItemIndex.takeIf { it != -1 }
              ?.let { player.getMediaItemAt( it ).mediaMetadata.title?.toString() }
    }

    val artwork = item.mediaMetadata.artworkUri?.toString()
    val colors by produceState( DefaultCoverColors, artwork ) {
        value = artwork?.let { coverColors( it ) } ?: DefaultCoverColors
    }
    val main by animateColorAsState( colors.main, tween(1200), label = "main" )
    val second by animateColorAsState( colors.second, tween(1200), label = "second" )
    val dark by animateColorAsState( colors.dark, tween(1200), label = "dark" )

    // Spectrum, updated about 30 times per second while this screen is shown
    val analyzer = remember { SpectrumAnalyzer( BANDS ) }
    var frame by remember { mutableLongStateOf( 0L ) }
    var bass by remember { mutableFloatStateOf( 0f ) }
    DisposableEffect( Unit ) {
        SpectrumTap.enabled = true
        onDispose { SpectrumTap.enabled = false }
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

    // Ambient mode
    var ambient by remember { mutableStateOf( false ) }
    var lastInput by remember { mutableLongStateOf( System.currentTimeMillis() ) }
    LaunchedEffect( lastInput, isPlaying, queueOpen ) {
        ambient = false
        if( isPlaying && !queueOpen ) {
            delay( AMBIENT_AFTER_MS )
            ambient = true
        }
    }
    val controlsAlpha by animateFloatAsState( if( ambient ) 0f else 1f, tween(700), label = "controls" )
    val visualizerHeight by animateDpAsState( if( ambient ) 190.dp else 120.dp, tween(900), label = "visualizer" )

    // The TV's screensaver shouldn't cover the player while music plays
    val view = LocalView.current
    DisposableEffect( isPlaying ) {
        view.keepScreenOn = isPlaying
        onDispose { view.keepScreenOn = false }
    }

    BackHandler( enabled = !queueOpen, onBack = onClose )

    val playFocus = remember { FocusRequester() }
    val queueFocus = remember { FocusRequester() }

    // Back to the Queue button once the panel has let go of the focus
    var queueWasOpen by remember { mutableStateOf( false ) }
    LaunchedEffect( queueOpen ) {
        if( queueOpen ) queueWasOpen = true
        else if( queueWasOpen ) {
            delay( 50 )
            runCatching { queueFocus.requestFocus() }
        }
    }
    LaunchedEffect( Unit ) {
        delay( 150 )
        runCatching { playFocus.requestFocus() }
    }

    fun seekBy( ms: Long ) {
        val duration = player.duration.takeIf { it > 0 } ?: return
        player.seekTo( (player.currentPosition + ms).coerceIn(0, duration) )
    }

    Box(
        Modifier
            .fillMaxSize()
            .background( Color.Black )
            .onPreviewKeyEvent { event ->
                if( event.type != KeyEventType.KeyDown ) return@onPreviewKeyEvent false
                lastInput = System.currentTimeMillis()
                // While in ambient mode the first press only wakes the controls up,
                // except for Back and media keys which always do their job
                ambient && event.key !in MediaKeys && event.key != Key.Back
            }
            .onKeyEvent { event ->
                if( event.type != KeyEventType.KeyDown ) return@onKeyEvent false
                when( event.key ) {
                    Key.MediaFastForward -> { seekBy( SEEK_STEP_MS ); true }
                    Key.MediaRewind -> { seekBy( -SEEK_STEP_MS ); true }
                    else -> false
                }
            }
    ) {
        Background( artwork, main, second, dark, isPlaying, { bass } )

        BoxWithConstraints( Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 40.dp) ) {
            val artSize = minOf( maxHeight * 0.62f, 340.dp )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding( bottom = 70.dp ).align( Alignment.Center )
            ) {
                Cover( artwork, artSize, main, second, isPlaying, { bass } )
                Spacer( Modifier.width(52.dp) )

                Column( Modifier.weight(1f) ) {
                    Text(
                        text = stringResource( R.string.tv_now_playing ).uppercase(),
                        color = lerp( main, Color.White, 0.55f ),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 3.sp,
                        modifier = Modifier.graphicsLayer { alpha = controlsAlpha }
                    )
                    Spacer( Modifier.height(10.dp) )
                    Text(
                        text = item.mediaMetadata.title?.toString().orEmpty(),
                        color = Color.White,
                        fontSize = 36.sp,
                        lineHeight = 40.sp,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer( Modifier.height(6.dp) )
                    Text(
                        text = listOfNotNull( item.mediaMetadata.artist, item.mediaMetadata.albumTitle )
                                   .joinToString( " · " ),
                        color = Color.White.copy( alpha = 0.72f ),
                        fontSize = 19.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Column( Modifier.graphicsLayer { alpha = controlsAlpha } ) {
                        Spacer( Modifier.height(26.dp) )
                        ProgressBar(
                            position = positionAndDuration.first,
                            duration = positionAndDuration.second,
                            accent = main,
                            onSeek = ::seekBy
                        )
                        Spacer( Modifier.height(22.dp) )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy( 18.dp )
                        ) {
                            TvButton( R.drawable.shuffle, "Shuffle", main, active = shuffle ) { player.toggleShuffleMode() }
                            TvButton( R.drawable.play_skip_back, "Previous", main ) { player.seekToPrevious() }
                            TvButton(
                                icon = if( isPlaying ) R.drawable.pause else R.drawable.play,
                                description = if( isPlaying ) "Pause" else "Play",
                                accent = main,
                                size = 76.dp,
                                primary = true,
                                modifier = Modifier.focusRequester( playFocus )
                            ) {
                                if( player.shouldBePlaying ) player.pause()
                                else {
                                    if( player.playbackState == Player.STATE_IDLE ) player.prepare()
                                    player.play()
                                }
                            }
                            TvButton( R.drawable.play_skip_forward, "Next", main ) { player.seekToNext() }
                            TvButton(
                                icon = if( repeatMode == Player.REPEAT_MODE_ONE ) R.drawable.repeatone else R.drawable.repeat,
                                description = "Repeat",
                                accent = main,
                                active = repeatMode != Player.REPEAT_MODE_OFF
                            ) { player.cycleRepeatMode() }
                            TvButton(
                                icon = if( isLiked ) R.drawable.heart else R.drawable.heart_outline,
                                description = "Like",
                                accent = main,
                                active = isLiked
                            ) {
                                CoroutineScope( Dispatchers.IO ).launch {
                                    YouTubeSync.toggleSongLike( view.context, item )
                                }
                            }
                        }
                        Spacer( Modifier.height(20.dp) )
                        Row( horizontalArrangement = Arrangement.spacedBy(14.dp) ) {
                            TvPill(
                                icon = R.drawable.playlist,
                                text = stringResource( R.string.tv_queue ),
                                accent = main,
                                modifier = Modifier.focusRequester( queueFocus )
                            ) { queueOpen = true }
                            TvPill(
                                icon = R.drawable.ellipsis_vertical,
                                text = stringResource( R.string.tv_more ),
                                accent = main
                            ) { onMore( item ) }
                        }
                        if( upNext != null ) {
                            Spacer( Modifier.height(22.dp) )
                            Text(
                                text = stringResource( R.string.tv_up_next, upNext ),
                                color = Color.White.copy( alpha = 0.55f ),
                                fontSize = 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            Clock( Modifier.align(Alignment.TopEnd).graphicsLayer { alpha = 1f - controlsAlpha * 0.6f } )
        }

        Visualizer(
            frame = { frame },
            levels = analyzer,
            from = main,
            to = second,
            modifier = Modifier.align( Alignment.BottomCenter )
                               .fillMaxWidth()
                               .height( visualizerHeight )
        )

        AnimatedVisibility(
            visible = queueOpen,
            enter = slideInHorizontally { it } + fadeIn(),
            exit = slideOutHorizontally { it } + fadeOut(),
            modifier = Modifier.align( Alignment.CenterEnd )
        ) {
            QueuePanel(
                player = player,
                currentIndex = currentIndex,
                accent = main,
                trapFocus = queueOpen,
                onClose = { queueOpen = false }
            )
        }
    }
}

private val MediaKeys = setOf(
    Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause, Key.MediaNext, Key.MediaPrevious,
    Key.MediaFastForward, Key.MediaRewind, Key.MediaStop
)

private suspend fun coverColors( url: String ): CoverColors? = withContext( Dispatchers.IO ) {
    val bitmap = ImageFactory.bitmap( url ) { allowHardware( false ) }.getOrNull() ?: return@withContext null
    val palette = Palette.from( bitmap ).maximumColorCount( 16 ).generate()
    val main = palette.vibrantSwatch ?: palette.lightVibrantSwatch ?: palette.dominantSwatch ?: return@withContext null
    val second = palette.darkVibrantSwatch ?: palette.mutedSwatch ?: palette.lightMutedSwatch ?: main
    val dark = palette.darkMutedSwatch ?: palette.darkVibrantSwatch ?: second
    CoverColors(
        main = Color( main.rgb ),
        second = Color( second.rgb ),
        dark = lerp( Color( dark.rgb ), Color.Black, 0.55f )
    )
}

/** Blurred cover filling the screen, slowly zooming, with colored light pulsing on the beat */
@Composable
private fun Background(
    artwork: String?,
    main: Color,
    second: Color,
    dark: Color,
    isPlaying: Boolean,
    bass: () -> Float
) {
    val motion = rememberInfiniteTransition( label = "background" )
    val zoom by motion.animateFloat( 1.15f, 1.35f, infiniteRepeatable(tween(28_000, easing = LinearEasing), RepeatMode.Reverse), label = "zoom" )
    val drift by motion.animateFloat( 0f, 2 * PI.toFloat(), infiniteRepeatable(tween(24_000, easing = LinearEasing)), label = "drift" )

    Box( Modifier.fillMaxSize().background(dark) ) {
        ImageFactory.AsyncImage(
            thumbnailUrl = artwork,
            contentScale = ContentScale.Crop,
            transformations = listOf( BlurTransformation(25, 0.2f) ),
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = zoom
                scaleY = zoom
                alpha = 0.55f
            }
        )

        Canvas( Modifier.fillMaxSize() ) {
            val beat = if( isPlaying ) bass() else 0f
            val r = size.maxDimension
            fun glow( color: Color, x: Float, y: Float, radius: Float, alpha: Float ) = drawCircle(
                brush = Brush.radialGradient(
                    listOf( color.copy(alpha = alpha), Color.Transparent ),
                    center = Offset( x, y ),
                    radius = radius
                ),
                radius = radius,
                center = Offset( x, y )
            )
            glow( main, size.width * (0.18f + 0.08f * sin(drift)), size.height * (0.25f + 0.1f * sin(drift * 2)), r * (0.55f + beat * 0.12f), 0.35f + beat * 0.3f )
            glow( second, size.width * (0.85f - 0.07f * sin(drift)), size.height * (0.8f + 0.06f * sin(drift * 3)), r * (0.5f + beat * 0.1f), 0.3f + beat * 0.25f )
            // Keep the text readable
            drawRect( Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.35f), Color.Black.copy(alpha = 0.55f))) )
            drawRect( Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.7f)) )
        }
    }
}

@Composable
private fun Cover(
    artwork: String?,
    side: Dp,
    main: Color,
    second: Color,
    isPlaying: Boolean,
    bass: () -> Float
) {
    val shape = RoundedCornerShape( 22.dp )
    val playingScale by animateFloatAsState( if( isPlaying ) 1f else 0.94f, tween(500), label = "cover" )

    Box(
        Modifier
            .size( side )
            .graphicsLayer {
                val pulse = 1f + bass() * 0.045f
                scaleX = playingScale * pulse
                scaleY = playingScale * pulse
            }
            .drawBehind {
                // Colored glow behind the cover
                val glow = size.width * (1.05f + bass() * 0.3f)
                drawCircle(
                    Brush.radialGradient(
                        0.45f to main.copy( alpha = 0.8f ),
                        0.7f to second.copy( alpha = 0.35f ),
                        1f to Color.Transparent,
                        center = center,
                        radius = glow
                    ),
                    radius = glow
                )
            }
    ) {
        ImageFactory.AsyncImage(
            thumbnailUrl = artwork,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
                               .clip( shape )
                               .border( 1.dp, Color.White.copy(alpha = 0.15f), shape )
        )
    }
}

/** Mirrored spectrum bars along the bottom of the screen */
@Composable
private fun Visualizer(
    frame: () -> Long,
    levels: SpectrumAnalyzer,
    from: Color,
    to: Color,
    modifier: Modifier
) = Canvas( modifier ) {
    frame()     // Redraw on every new spectrum frame
    val bands = levels.levels
    val slot = size.width / bands.size
    val barWidth = slot * 0.62f
    val baseline = size.height * 0.78f
    val maxHeight = baseline - 4f
    val brush = Brush.verticalGradient( listOf(to, from), startY = baseline - maxHeight, endY = baseline )

    bands.forEachIndexed { i, level ->
        val h = (level * maxHeight).coerceAtLeast( 3f )
        val x = i * slot + (slot - barWidth) / 2
        drawRoundRect(
            brush = brush,
            topLeft = Offset( x, baseline - h ),
            size = Size( barWidth, h ),
            cornerRadius = CornerRadius( barWidth / 2 ),
            alpha = 0.9f
        )
        // Reflection
        val rh = h * 0.35f
        drawRoundRect(
            color = from,
            topLeft = Offset( x, baseline + 4f ),
            size = Size( barWidth, rh.coerceAtMost(size.height - baseline - 4f) ),
            cornerRadius = CornerRadius( barWidth / 2 ),
            alpha = 0.18f
        )
    }
}

@Composable
private fun ProgressBar(
    position: Long,
    duration: Long,
    accent: Color,
    onSeek: (Long) -> Unit
) {
    var focused by remember { mutableStateOf( false ) }
    val height by animateDpAsState( if( focused ) 10.dp else 6.dp, label = "progress" )
    val fraction = if( duration > 0 ) (position.toFloat() / duration).coerceIn( 0f, 1f ) else 0f

    Column {
        Box(
            Modifier
                .fillMaxWidth()
                .height( 22.dp )
                .onFocusChanged { focused = it.isFocused }
                .onKeyEvent { event ->
                    if( event.type != KeyEventType.KeyDown ) return@onKeyEvent false
                    when( event.key ) {
                        Key.DirectionLeft -> { onSeek( -SEEK_STEP_MS ); true }
                        Key.DirectionRight -> { onSeek( SEEK_STEP_MS ); true }
                        else -> false
                    }
                }
                .focusable(),
            contentAlignment = Alignment.CenterStart
        ) {
            Canvas( Modifier.fillMaxWidth().height(height) ) {
                val r = CornerRadius( size.height / 2 )
                drawRoundRect( Color.White.copy(alpha = 0.18f), cornerRadius = r )
                drawRoundRect(
                    Brush.horizontalGradient( listOf(accent, lerp(accent, Color.White, 0.5f)) ),
                    size = Size( size.width * fraction, size.height ),
                    cornerRadius = r
                )
                if( focused )
                    drawCircle( Color.White, radius = size.height * 1.1f, center = Offset(size.width * fraction, size.height / 2) )
            }
        }
        Row( Modifier.fillMaxWidth() ) {
            Text( formatTime(position), color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp )
            Spacer( Modifier.weight(1f) )
            Text(
                text = if( focused ) "◀ −10s   +10s ▶" else "",
                color = Color.White.copy( alpha = 0.6f ),
                fontSize = 13.sp
            )
            Spacer( Modifier.weight(1f) )
            Text( formatTime(duration), color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp )
        }
    }
}

/** Round button that grows and lights up when the remote focuses it */
@Composable
private fun TvButton(
    @DrawableRes icon: Int,
    description: String,
    accent: Color,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    primary: Boolean = false,
    active: Boolean = false,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf( false ) }
    val scale by animateFloatAsState( if( focused ) 1.18f else 1f, tween(180), label = "scale" )
    val background = when {
        focused -> Color.White
        primary -> accent
        else -> Color.White.copy( alpha = 0.1f )
    }
    val tint = when {
        focused -> Color.Black
        primary -> Color.White
        active -> lerp( accent, Color.White, 0.35f )
        else -> Color.White.copy( alpha = 0.85f )
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size( size )
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .drawBehind {
                if( focused || primary )
                    drawCircle(
                        Brush.radialGradient(
                            listOf( accent.copy(alpha = if( focused ) 0.7f else 0.45f), Color.Transparent ),
                            center = center,
                            radius = this.size.width
                        ),
                        radius = this.size.width
                    )
            }
            .clip( CircleShape )
            .background( background )
            .onFocusChanged { focused = it.isFocused }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
    ) {
        Icon(
            painter = painterResource( icon ),
            contentDescription = description,
            tint = tint,
            modifier = Modifier.size( size * 0.42f )
        )
        if( active && !focused && !primary )
            Box(
                Modifier.align( Alignment.BottomCenter )
                        .padding( bottom = 7.dp )
                        .size( 5.dp )
                        .clip( CircleShape )
                        .background( tint )
            )
    }
}

/** Rounded button with icon and text, for the secondary actions */
@Composable
private fun TvPill(
    @DrawableRes icon: Int,
    text: String,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf( false ) }
    val scale by animateFloatAsState( if( focused ) 1.08f else 1f, tween(160), label = "pill" )
    val content = if( focused ) Color.Black else Color.White.copy( alpha = 0.9f )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip( RoundedCornerShape(50) )
            .background( if( focused ) Color.White else Color.White.copy(alpha = 0.1f) )
            .border( 1.dp, if( focused ) accent else Color.White.copy(alpha = 0.12f), RoundedCornerShape(50) )
            .onFocusChanged { focused = it.isFocused }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding( horizontal = 18.dp, vertical = 10.dp )
    ) {
        Icon( painterResource(icon), null, tint = content, modifier = Modifier.size(18.dp) )
        Spacer( Modifier.width(10.dp) )
        Text( text, color = content, fontSize = 15.sp, fontWeight = FontWeight.SemiBold )
    }
}

/**
 * Songs in the queue, sliding in from the right. Arrows move through it,
 * center plays the song and Back closes it.
 */
@Composable
private fun QueuePanel(
    player: StatefulPlayer,
    currentIndex: Int,
    accent: Color,
    trapFocus: Boolean,
    onClose: () -> Unit
) {
    val timeline by player.currentTimelineState.collectAsState()
    val items = remember( timeline ) {
        val window = Timeline.Window()
        List( timeline.windowCount ) { timeline.getWindow( it, window ).mediaItem }
    }
    val listState = rememberLazyListState()
    val currentFocus = remember { FocusRequester() }

    BackHandler( enabled = trapFocus, onBack = onClose )
    LaunchedEffect( Unit ) {
        listState.scrollToItem( (currentIndex - 2).coerceAtLeast(0) )
        delay( 120 )
        runCatching { currentFocus.requestFocus() }
    }

    Column(
        Modifier
            .fillMaxHeight()
            .width( 420.dp )
            .background( Brush.horizontalGradient(listOf(Color(0xE6101018), Color(0xF5101018))) )
            // Arrows stay inside the panel; the controls behind it are out of reach
            .focusProperties { onExit = { if( trapFocus ) cancelFocusChange() } }
            .focusGroup()
            .padding( vertical = 32.dp )
    ) {
        Text(
            text = stringResource( R.string.tv_queue ),
            color = Color.White,
            fontSize = 24.sp,
            fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.padding( horizontal = 28.dp )
        )
        Text(
            text = "${currentIndex + 1} / ${items.size}",
            color = Color.White.copy( alpha = 0.55f ),
            fontSize = 14.sp,
            modifier = Modifier.padding( horizontal = 28.dp, vertical = 4.dp )
        )
        Spacer( Modifier.height(12.dp) )

        LazyColumn( state = listState, modifier = Modifier.fillMaxSize() ) {
            itemsIndexed( items ) { index, mediaItem ->
                var focused by remember { mutableStateOf( false ) }
                val isCurrent = index == currentIndex
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding( horizontal = 14.dp, vertical = 3.dp )
                        .clip( RoundedCornerShape(14.dp) )
                        .background(
                            when {
                                focused -> Color.White
                                isCurrent -> accent.copy( alpha = 0.28f )
                                else -> Color.Transparent
                            }
                        )
                        .then( if( isCurrent ) Modifier.focusRequester( currentFocus ) else Modifier )
                        .onFocusChanged { focused = it.isFocused }
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            player.seekToDefaultPosition( index )
                            if( !player.shouldBePlaying ) {
                                if( player.playbackState == Player.STATE_IDLE ) player.prepare()
                                player.play()
                            }
                        }
                        .padding( 10.dp )
                ) {
                    ImageFactory.AsyncImage(
                        thumbnailUrl = mediaItem.mediaMetadata.artworkUri?.toString(),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size( 48.dp ).clip( RoundedCornerShape(8.dp) )
                    )
                    Spacer( Modifier.width(14.dp) )
                    Column( Modifier.weight(1f) ) {
                        Text(
                            text = mediaItem.mediaMetadata.title?.toString().orEmpty(),
                            color = if( focused ) Color.Black else Color.White,
                            fontSize = 16.sp,
                            fontWeight = if( isCurrent ) FontWeight.ExtraBold else FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = mediaItem.mediaMetadata.artist?.toString().orEmpty(),
                            color = if( focused ) Color.Black.copy( alpha = 0.65f ) else Color.White.copy( alpha = 0.6f ),
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if( isCurrent )
                        Icon(
                            painter = painterResource( R.drawable.musical_notes ),
                            contentDescription = null,
                            tint = if( focused ) Color.Black else lerp( accent, Color.White, 0.4f ),
                            modifier = Modifier.size( 18.dp )
                        )
                }
            }
        }
    }
}

@Composable
private fun Clock( modifier: Modifier ) {
    val time by produceState( "" ) {
        val format = SimpleDateFormat( "HH:mm", Locale.getDefault() )
        while( true ) {
            value = format.format( Date() )
            delay( 1000 )
        }
    }
    Text( time, color = Color.White.copy(alpha = 0.85f), fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = modifier )
}

private fun formatTime( ms: Long ): String {
    if( ms <= 0 ) return "0:00"
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if( h > 0 ) "%d:%02d:%02d".format( h, m, s ) else "%d:%02d".format( m, s )
}
