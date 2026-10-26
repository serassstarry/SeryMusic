@file:androidx.media3.common.util.UnstableApi

package app.kreate.android.service.player

import androidx.media3.common.C
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Copies the decoded audio that ExoPlayer is about to play, so the TV player
 * can draw a real spectrum without asking for the microphone permission
 * that [android.media.audiofx.Visualizer] needs.
 *
 * Does nothing while [enabled] is false.
 */
object SpectrumTap : TeeAudioProcessor.AudioBufferSink {

    private const val RING_SIZE = 1 shl 16      // ~1.4s of mono audio at 48kHz

    /** Audio is copied a bit before it's heard, because of the audio track buffer */
    private const val OUTPUT_LATENCY_MS = 250

    @Volatile var enabled = false

    private val ring = FloatArray( RING_SIZE )
    @Volatile private var written = 0L
    @Volatile private var lastWriteNanos = 0L
    @Volatile var sampleRate = 44_100
        private set
    private var channels = 2
    private var encoding = C.ENCODING_PCM_16BIT

    override fun flush( sampleRateHz: Int, channelCount: Int, encoding: Int ) {
        sampleRate = sampleRateHz
        channels = channelCount.coerceAtLeast( 1 )
        this.encoding = encoding
    }

    override fun handleBuffer( buffer: ByteBuffer ) {
        if( !enabled ) return

        val data = buffer.duplicate().order( ByteOrder.nativeOrder() )
        var pos = written
        when( encoding ) {
            C.ENCODING_PCM_16BIT -> {
                val frames = data.remaining() / (2 * channels)
                repeat( frames ) {
                    var sum = 0f
                    repeat( channels ) { sum += data.short / 32768f }
                    ring[(pos++ and (RING_SIZE - 1).toLong()).toInt()] = sum / channels
                }
            }
            C.ENCODING_PCM_FLOAT -> {
                val frames = data.remaining() / (4 * channels)
                repeat( frames ) {
                    var sum = 0f
                    repeat( channels ) { sum += data.float }
                    ring[(pos++ and (RING_SIZE - 1).toLong()).toInt()] = sum / channels
                }
            }
            else -> return
        }
        written = pos
        lastWriteNanos = System.nanoTime()
    }

    /**
     * Fills [out] with the most recent samples that are being heard right now.
     *
     * @return false when no audio arrived lately (paused, buffering or tap not working)
     */
    fun latest( out: FloatArray ): Boolean {
        if( System.nanoTime() - lastWriteNanos > 400_000_000L ) return false

        val end = written - sampleRate.toLong() * OUTPUT_LATENCY_MS / 1000
        val start = end - out.size
        if( start < 0 ) return false
        for( i in out.indices )
            out[i] = ring[((start + i) and (RING_SIZE - 1).toLong()).toInt()]
        return true
    }
}

/**
 * Turns the samples from [SpectrumTap] into [bandCount] bars between 0 and 1,
 * spaced like the ear hears them (logarithmic), with smooth falling.
 */
class SpectrumAnalyzer( val bandCount: Int, private val fftSize: Int = 1024 ) {

    private val samples = FloatArray( fftSize )
    private val re = FloatArray( fftSize )
    private val im = FloatArray( fftSize )
    private val window = FloatArray( fftSize ) { 0.5f - 0.5f * cos( 2 * PI * it / (fftSize - 1) ).toFloat() }
    /** Current bars, between 0 and 1 */
    val levels = FloatArray( bandCount )
    private val dbs = FloatArray( bandCount )
    /** Loudest band lately, in dB: the top of the bars */
    private var reference = -30f

    private companion object {
        /** Decibels shown between an empty bar and a full one */
        const val RANGE_DB = 42f
    }

    /** Average level of the lowest bands: the beat */
    var bass = 0f
        private set

    /**
     * @return current bars; they fall to 0 when there's no audio
     */
    fun update(): FloatArray {
        val hasAudio = SpectrumTap.latest( samples )
        if( !hasAudio ) {
            for( i in levels.indices ) levels[i] *= 0.88f
            bass *= 0.88f
            return levels
        }

        for( i in 0 until fftSize ) {
            re[i] = samples[i] * window[i]
            im[i] = 0f
        }
        fft( re, im )

        val binHz = SpectrumTap.sampleRate.toFloat() / fftSize
        val minHz = 40f
        // Many songs have little above 12kHz, those bars would barely move
        val maxHz = minOf( 12_000f, SpectrumTap.sampleRate / 2f )
        var loudest = -120f
        for( band in 0 until bandCount ) {
            val lo = minHz * (maxHz / minHz).pow( band.toFloat() / bandCount )
            val hi = minHz * (maxHz / minHz).pow( (band + 1).toFloat() / bandCount )
            val from = (lo / binHz).toInt().coerceIn( 1, fftSize / 2 - 1 )
            val to = (hi / binHz).toInt().coerceIn( from, fftSize / 2 - 1 )
            var energy = 0f
            for( bin in from..to )
                energy += re[bin] * re[bin] + im[bin] * im[bin]

            // Average power in decibels, with a gentle boost for higher notes
            // (music has much more energy in the bass)
            val centre = sqrt( lo * hi )
            dbs[band] = 10 * log10( energy / (to - from + 1) + 1e-12f ) + 3f * log2( centre / 100f )
            loudest = max( loudest, dbs[band] )
        }

        // Follow the song's loudness, so quiet and loud songs both fill the bars
        reference = if( loudest > reference ) loudest else reference - 0.08f
        for( band in 0 until bandCount ) {
            val value = ((dbs[band] - reference + RANGE_DB) / RANGE_DB).coerceIn( 0f, 1f )
            // Rise fast, fall smoothly
            levels[band] = if( value > levels[band] ) value else max( value, levels[band] - 0.045f )
        }

        val bassBands = (bandCount / 8).coerceAtLeast( 1 )
        bass = levels.take( bassBands ).average().toFloat()
        return levels
    }

    /** In-place radix-2 FFT */
    private fun fft( re: FloatArray, im: FloatArray ) {
        val n = re.size
        var j = 0
        for( i in 1 until n ) {
            var bit = n shr 1
            while( j and bit != 0 ) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if( i < j ) {
                re[i] = re[j].also { re[j] = re[i] }
                im[i] = im[j].also { im[j] = im[i] }
            }
        }
        var len = 2
        while( len <= n ) {
            val angle = -2 * PI / len
            val wRe = cos( angle ).toFloat()
            val wIm = sin( angle ).toFloat()
            for( i in 0 until n step len ) {
                var curRe = 1f
                var curIm = 0f
                for( k in 0 until len / 2 ) {
                    val a = i + k
                    val b = a + len / 2
                    val tRe = re[b] * curRe - im[b] * curIm
                    val tIm = re[b] * curIm + im[b] * curRe
                    re[b] = re[a] - tRe
                    im[b] = im[a] - tIm
                    re[a] += tRe
                    im[a] += tIm
                    val nextRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = nextRe
                }
            }
            len = len shl 1
        }
    }
}
