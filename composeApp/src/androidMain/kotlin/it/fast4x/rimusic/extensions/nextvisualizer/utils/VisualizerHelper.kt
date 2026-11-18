package it.fast4x.rimusic.extensions.nextvisualizer.utils

import app.kreate.android.service.player.SpectrumTap
import app.kreate.android.service.player.fftInPlace
import kotlin.math.hypot

/**
 * Gives the painters the same data `android.media.audiofx.Visualizer` did (8-bit waveform
 * and FFT), but taken from what ExoPlayer is playing through [SpectrumTap]. The audiofx
 * Visualizer needed the microphone permission and doesn't work on some devices.
 *
 * Call [release] when the visualizer goes away.
 */
class VisualizerHelper(
    @Suppress("UNUSED_PARAMETER") sessionId: Int = 0
) {

    companion object {
        /** Same as the largest audiofx capture size */
        const val CAPTURE_SIZE = 1024
        /** Music sits far below full scale, lift it so the painters have something to draw */
        private const val FFT_GAIN = 6f
    }

    private val samples = FloatArray( CAPTURE_SIZE )
    private val re = FloatArray( CAPTURE_SIZE )
    private val im = FloatArray( CAPTURE_SIZE )
    private val fftBuff = ByteArray( CAPTURE_SIZE )
    private val waveBuff = ByteArray( CAPTURE_SIZE ) { -128 }      // 0x80: silence
    private val fftM = DoubleArray( CAPTURE_SIZE / 2 - 1 )
    private var released = false

    init {
        SpectrumTap.acquire()
    }

    /** Audio playing right now, or silence when paused */
    private fun capture(): Boolean {
        val hasAudio = SpectrumTap.latest( samples, CAPTURE_SIZE )
        if( !hasAudio ) samples.fill( 0f )
        return hasAudio
    }

    /**
     * Same layout as audiofx: [0] DC, [1] Nyquist, then real and imaginary pairs.
     */
    fun getFft(): ByteArray {
        capture()
        for( i in 0 until CAPTURE_SIZE ) {
            re[i] = samples[i]
            im[i] = 0f
        }
        fftInPlace( re, im )

        val scale = 128f / (CAPTURE_SIZE / 2f) * FFT_GAIN
        fun toByte( v: Float ) = (v * scale).coerceIn( -128f, 127f ).toInt().toByte()
        fftBuff[0] = toByte( re[0] )
        fftBuff[1] = toByte( re[CAPTURE_SIZE / 2] )
        for( k in 1 until CAPTURE_SIZE / 2 ) {
            fftBuff[2 * k] = toByte( re[k] )
            fftBuff[2 * k + 1] = toByte( im[k] )
        }
        return fftBuff
    }

    /** Unsigned 8-bit samples, 128 is silence, like audiofx */
    fun getWave(): ByteArray {
        capture()
        for( i in 0 until CAPTURE_SIZE )
            waveBuff[i] = ((samples[i].coerceIn( -1f, 1f ) * 127f) + 128f).toInt().toByte()
        return waveBuff
    }

    fun getFftMagnitude(): DoubleArray {
        getFft()
        for( k in fftM.indices ) {
            val i = (k + 1) * 2
            fftM[k] = hypot( fftBuff[i].toDouble(), fftBuff[i + 1].toDouble() )
        }
        return fftM
    }

    /**
     * Get Fft values from startHz to endHz
     */
    fun getFftMagnitudeRange( startHz: Int, endHz: Int ): DoubleArray =
        getFftMagnitude().copyOfRange( hzToFftIndex(startHz), hzToFftIndex(endHz) )

    /**
     * kth frequency = k * Fs / n
     */
    fun hzToFftIndex( Hz: Int ): Int =
        (Hz.toLong() * CAPTURE_SIZE / SpectrumTap.sampleRate).toInt().coerceIn( 0, fftM.size - 1 )

    /**
     * Stop copying audio when the visualizer isn't shown anymore
     */
    fun release() {
        if( released ) return
        released = true
        SpectrumTap.release()
    }
}
