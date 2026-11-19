package app.kreate.android.mascot

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import app.kreate.android.R
import app.kreate.android.service.player.SpectrumAnalyzer
import app.kreate.android.service.player.SpectrumTap
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

private val NOTES = arrayOf( "♪", "♫", "♬", "♩" )
private const val HEART = "♥"

/** A note or heart floating away from the mascot, positions in 0..1 of the mascot's box */
private class Particle(
    val glyph: String,
    var x: Float,
    var y: Float,
    val vx: Float,
    val vy: Float,
    val size: Float,
    val color: Color,
    val spin: Float,
    val lifeMs: Float,
    var ageMs: Float = 0f
)

/**
 * Sery, the app's chibi mascot, alive: she floats and sways, dances to the bass and
 * sends notes out of her headphones while music plays ([playing]), and jumps throwing
 * hearts when tapped.
 *
 * @param playing whether music is playing right now; she only listens to the audio then
 * @param accent color of the notes
 */
@Composable
fun SeryMascot(
    modifier: Modifier = Modifier,
    playing: Boolean = false,
    accent: Color = Color(0xFF7DD3FC),
    accent2: Color = Color(0xFFC084FC),
    interactive: Boolean = true
) {
    // Idle motion: float, sway and breathe, each on its own period so it never looks looped
    val idle = rememberInfiniteTransition( label = "mascot" )
    val float by idle.animateFloat( 0f, 1f, infiniteRepeatable(tween(3200, easing = LinearEasing)), label = "float" )
    val sway by idle.animateFloat( 0f, 1f, infiniteRepeatable(tween(4600, easing = LinearEasing)), label = "sway" )
    val breathe by idle.animateFloat( 0f, 1f, infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Restart), label = "breathe" )

    // Beat: from the music while playing
    var beat by remember { mutableFloatStateOf( 0f ) }
    var groove by remember { mutableFloatStateOf( 0f ) }       // slowly following the beat, for the head bob
    val particles = remember { mutableListOf<Particle>() }
    var frame by remember { mutableLongStateOf( 0L ) }

    if( playing )
        DisposableEffect( Unit ) {
            SpectrumTap.acquire()
            onDispose { SpectrumTap.release() }
        }

    val analyzer = remember { SpectrumAnalyzer( 16 ) }
    LaunchedEffect( playing ) {
        var last = 0L
        var sinceNote = 0f
        var lastBass = 0f
        while( true ) withFrameNanos { now ->
            val dt = if( last == 0L ) 16f else ((now - last) / 1_000_000f).coerceAtMost( 64f )
            last = now

            if( playing ) {
                analyzer.update()
                beat = analyzer.bass
            } else
                beat *= 0.9f
            groove += (beat - groove) * 0.15f

            // Notes: a steady stream while playing, an extra one on strong kicks
            sinceNote += dt
            val kick = playing && beat > 0.55f && beat - lastBass > 0.12f
            lastBass = beat
            if( playing && (sinceNote > 520f || kick) ) {
                sinceNote = 0f
                val fromLeft = Random.nextBoolean()
                particles += Particle(
                    glyph = NOTES.random(),
                    x = if( fromLeft ) 0.24f else 0.76f,
                    y = 0.32f,
                    vx = (if( fromLeft ) -1 else 1) * (0.00006f + Random.nextFloat() * 0.00008f),
                    vy = -(0.00018f + Random.nextFloat() * 0.0001f),
                    size = 0.09f + Random.nextFloat() * 0.06f,
                    color = if( Random.nextBoolean() ) accent else accent2,
                    spin = (Random.nextFloat() - 0.5f) * 0.12f,
                    lifeMs = 2200f + Random.nextFloat() * 900f
                )
            }

            val iterator = particles.iterator()
            while( iterator.hasNext() ) {
                val p = iterator.next()
                p.ageMs += dt
                p.x += p.vx * dt
                p.y += p.vy * dt
                if( p.ageMs >= p.lifeMs || p.y < -0.2f ) iterator.remove()
            }
            frame = now
        }
    }

    // Tap: jump with a little spin and a burst of hearts
    val scope = rememberCoroutineScope()
    val jump = remember { Animatable( 0f ) }
    val spin = remember { Animatable( 0f ) }
    var squash by remember { mutableStateOf( 0f ) }
    fun react() = scope.launch {
        repeat( 6 ) {
            particles += Particle(
                glyph = HEART,
                x = 0.5f + (Random.nextFloat() - 0.5f) * 0.3f,
                y = 0.45f,
                vx = (Random.nextFloat() - 0.5f) * 0.0005f,
                vy = -(0.00025f + Random.nextFloat() * 0.0002f),
                size = 0.08f + Random.nextFloat() * 0.05f,
                color = Color(0xFFF472B6),
                spin = (Random.nextFloat() - 0.5f) * 0.2f,
                lifeMs = 1300f + Random.nextFloat() * 500f
            )
        }
        launch {
            spin.snapTo( 0f )
            spin.animateTo( 1f, tween(650) )
        }
        squash = 1f
        jump.animateTo( 1f, tween(220) )
        jump.animateTo( 0f, spring(dampingRatio = 0.35f, stiffness = 400f) )
        squash = 0f
    }

    val notePaint = remember {
        Paint( Paint.ANTI_ALIAS_FLAG ).apply {
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
    }

    BoxWithConstraints(
        contentAlignment = Alignment.Center,
        modifier = modifier
    ) {
        val box = maxOf( constraints.maxWidth, constraints.maxHeight ).toFloat()

        Image(
            painter = painterResource( R.drawable.sery_mascot ),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val floatY = sin( float * 2 * PI ).toFloat() * 0.025f
                    val bob = groove * 0.05f + beat * 0.02f
                    translationY = (floatY - bob - jump.value * 0.16f) * size.height
                    rotationZ = sin( sway * 2 * PI ).toFloat() * 3f +
                            sin( frame / 1_000_000_000.0 * 2 * PI * 1.1 ).toFloat() * groove * 6f +
                            spin.value * 360f * (if( spin.value < 1f ) 1f else 0f)
                    val breath = sin( breathe * 2 * PI ).toFloat() * 0.012f
                    val pump = beat * 0.05f
                    scaleY = 1f + breath + pump - jump.value * 0.04f * squash
                    scaleX = 1f - breath * 0.5f + pump * 0.6f + jump.value * 0.03f * squash
                    transformOrigin = TransformOrigin( 0.5f, 0.92f )
                }
                .then(
                    if( interactive )
                        Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { react() }
                    else Modifier
                )
        )

        // Notes and hearts on top
        Box(
            Modifier
                .fillMaxSize()
                .drawWithContent {
                    frame        // redraw every frame
                    drawContent()
                    drawIntoCanvas { canvas ->
                        particles.forEach { p ->
                            val t = p.ageMs / p.lifeMs
                            val alpha = when {
                                t < 0.15f -> t / 0.15f
                                t > 0.7f -> (1f - t) / 0.3f
                                else -> 1f
                            }.coerceIn( 0f, 1f )
                            notePaint.color = p.color.copy( alpha = alpha ).toArgb()
                            notePaint.textSize = p.size * box
                            val wobble = sin( p.ageMs / 260f ).toFloat() * 0.015f
                            val cx = (p.x + wobble) * size.width
                            val cy = p.y * size.height
                            canvas.nativeCanvas.save()
                            canvas.nativeCanvas.rotate( p.spin * p.ageMs / 10f, cx, cy )
                            canvas.nativeCanvas.drawText( p.glyph, cx, cy, notePaint )
                            canvas.nativeCanvas.restore()
                        }
                    }
                }
        )
    }
}
