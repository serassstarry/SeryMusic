package app.kreate.android.service.player

import android.content.Context
import android.media.audiofx.LoudnessEnhancer
import android.widget.Toast
import androidx.annotation.AnyThread
import androidx.annotation.MainThread
import androidx.compose.ui.util.fastForEach
import androidx.compose.ui.util.fastMap
import androidx.compose.ui.util.fastMapIndexed
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import app.kreate.android.Preferences
import app.kreate.android.R
import app.kreate.database.models.PersistentQueue
import app.kreate.di.clearCachedStreamUrlOf
import com.metrolist.music.utils.YTPlayerUtils
import com.metrolist.music.utils.cipher.CipherDeobfuscator
import it.fast4x.rimusic.Database
import it.fast4x.rimusic.enums.NotificationButtons
import it.fast4x.rimusic.enums.QueueLoopType
import it.fast4x.rimusic.service.LoginRequiredException
import it.fast4x.rimusic.service.MissingDecipherKeyException
import it.fast4x.rimusic.service.NoInternetException
import it.fast4x.rimusic.service.PlayableFormatNotFoundException
import it.fast4x.rimusic.service.UnknownException
import it.fast4x.rimusic.service.UnplayableException
import it.fast4x.rimusic.utils.mediaItems
import it.fast4x.rimusic.utils.playNext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.knighthat.utils.Toaster
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalAtomicApi::class)
@UnstableApi
class ExoPlayerListener(
    private val player: StatefulPlayer,
    private val mediaSession: MediaSession,
    private val waitingForNetwork: MutableStateFlow<Boolean>,
    private val sendOpenEqualizerIntent: () -> Unit,
    private val sendCloseEqualizerIntent: () -> Unit,
    private val onMediaTransition: (MediaItem?) -> Unit
): Player.Listener, KoinComponent {

    private val context: Context by inject()

    private var volumeNormalizationJob: Job = Job()
    private var errorTimestamp = 0L
    private var lastErrorMessage = ""
    /**
     * Number of stream-refresh retries per song, see [tryRecoverRejectedStream].
     * Only accessed from main thread.
     */
    private val streamRetries = mutableMapOf<String, Int>()

    var loudnessEnhancer: LoudnessEnhancer? = null
        private set

    /**
     * Requires [Preferences.ENABLE_PERSISTENT_QUEUE] to be **enabled** to work.
     */
    @AnyThread
    fun saveQueueToDatabase() {
        if( !Preferences.ENABLE_PERSISTENT_QUEUE.value ) return

        CoroutineScope( Dispatchers.Default ).launch {
            val (queue, index, playerPos) = withContext(Dispatchers.Main ) {
                // Any call related to [Player] must happen on main thread
                with( player ) {
                    Triple(currentTimeline.mediaItems, currentMediaItemIndex, currentPosition)
                }
            }
            if( queue.isEmpty() ) return@launch

            val queueItems = queue.fastMapIndexed { i, m ->
                PersistentQueue(
                    songId = m.mediaId,
                    position = if( i == index ) playerPos else null
                )
            }
            Database.asyncTransaction {
                queueTable.deleteAll()
                queue.forEach( ::insertIgnore )
                queueTable.insertIgnore( queueItems )
            }
        }
    }

    /**
     * (Re)render media control in notification area.
     */
    @AnyThread
    fun updateMediaControl( context: Context, player: Player ) {
        CoroutineScope(Dispatchers.Default ).launch {
            var firstButton: CommandButton? = null
            var secondButton: CommandButton? = null
            val buttons = mutableListOf<CommandButton>()

            NotificationButtons.entries
                .fastMap { it to PlaybackController.makeButton( context, player, it ) }
                .fastForEach { (nBtn, cmdBtn) ->
                    when (nBtn) {
                        Preferences.MEDIA_NOTIFICATION_FIRST_ICON.value -> firstButton = cmdBtn
                        Preferences.MEDIA_NOTIFICATION_SECOND_ICON.value -> secondButton = cmdBtn
                        else -> buttons.add( cmdBtn )
                    }
                }

            val layoutButton = buildList {
                firstButton?.also( ::add )
                secondButton?.also( ::add )
                addAll( buttons )
            }

            withContext( Dispatchers.Main ) {
                mediaSession.setMediaButtonPreferences( layoutButton )
            }
        }
    }

    private fun loadFromRadio( reason: Int ) {
        // Don't fetch more item if:
        // - Feature is disabled
        // - When song is repeated
        // - Start new queue
        if( !Preferences.QUEUE_AUTO_APPEND.value
            || reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
            || reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED
        ) return

        val positionToLast = player.mediaItemCount - player.currentMediaItemIndex
        // Make sure only add when about 10 songs to the last song in queue
        // TODO: Add slider in settings to let user change number of songs
        if( positionToLast <= 10 && !player.isLoadingRadio() )
            player.startRadio()
    }

    @MainThread
    private fun traverseErrorStack( t: Throwable ): Throwable =
        when( t ) {
            is PlayableFormatNotFoundException,
            is UnplayableException,
            is LoginRequiredException,
            is NoInternetException,
            is UnknownException,
            is MissingDecipherKeyException -> t

            else -> t.cause?.let( ::traverseErrorStack ) ?: t
        }

    @MainThread
    private fun printErrorMessage( errMsg: String )  {
        // If the same error is set within 10s, it'll be ignored.
        val timeWindow = errorTimestamp + 10.seconds.inWholeMilliseconds

        if( errMsg == lastErrorMessage
            && System.currentTimeMillis() <= timeWindow
        ) return

        lastErrorMessage = errMsg
        // When field is successfully set, update timestamp.
        errorTimestamp = System.currentTimeMillis()
        // Finally, print the error if not blank
        if( errMsg.isNotBlank() )
            Toaster.e( errMsg, Toast.LENGTH_LONG )
    }

    /**
     * @return HTTP status code of the first [HttpDataSource.InvalidResponseCodeException]
     * in [t]'s cause chain, or `null` if there's none.
     */
    private fun httpResponseCodeOf( t: Throwable ): Int? {
        var cause: Throwable? = t
        while( cause != null ) {
            if( cause is HttpDataSource.InvalidResponseCodeException )
                return cause.responseCode
            cause = cause.cause
        }
        return null
    }

    /**
     * YouTube rejecting a stream url (403/410) means the url is expired, or was
     * deciphered with a stale/wrong player config. Mark the main client as failed
     * for this song so the next resolution falls through to the fallback clients,
     * drop the cached url, refresh cipher configs, then retry at the same position.
     *
     * @return `true` if a retry was scheduled, `false` if error isn't recoverable
     * or song already used up all retries.
     */
    @MainThread
    private fun tryRecoverRejectedStream( error: PlaybackException ): Boolean {
        val code = httpResponseCodeOf( error )
        if( code != 403 && code != 410 ) return false
        val mediaId = player.currentMediaItem?.mediaId ?: return false

        val attempts = streamRetries[mediaId] ?: 0
        if( attempts >= MAX_STREAM_RETRIES ) {
            streamRetries.remove( mediaId )
            return false
        }
        streamRetries[mediaId] = attempts + 1

        YTPlayerUtils.markWebRemixFailed( mediaId )
        clearCachedStreamUrlOf( mediaId )

        val position = player.currentPosition
        CoroutineScope( Dispatchers.IO ).launch {
            // Rate-limited, returns `true` when configs changed. In that case,
            // previous failures were caused by the old configs, give main client another chance.
            runCatching {
                if( CipherDeobfuscator.isInitialized() && CipherDeobfuscator.onStreamRejected() )
                    YTPlayerUtils.clearWebRemixFailures()
            }

            withContext( Dispatchers.Main ) {
                // User may have moved on to another song
                if( player.currentMediaItem?.mediaId != mediaId ) return@withContext

                player.seekTo( position )
                player.prepare()
            }
        }

        return true
    }

    override fun onPlaybackStateChanged( playbackState: Int ) {
        if( playbackState == Player.STATE_READY )
            player.currentMediaItem?.mediaId?.also( streamRetries::remove )
    }

    override fun onPlayWhenReadyChanged( playWhenReady: Boolean, reason: Int ) = saveQueueToDatabase()

    override fun onRepeatModeChanged( repeatMode: Int ) {
        updateMediaControl( context, this.player )
        Preferences.QUEUE_LOOP_TYPE.value = QueueLoopType.from( repeatMode )
    }

    override fun onMediaItemTransition( mediaItem: MediaItem?, reason: Int ) {
        if ( player.playerError != null ) player.prepare()

        // Songs left behind mid-retry never reach STATE_READY, drop their counters
        streamRetries.keys.retainAll { it == mediaItem?.mediaId }

        loadFromRadio(reason)
        onMediaTransition( mediaItem )
    }

    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        if ( reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED )
            saveQueueToDatabase()
    }

    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
        updateMediaControl( context, this.player )
        if (shuffleModeEnabled) {
            val shuffledIndices = IntArray(player.mediaItemCount) { it }
            shuffledIndices.shuffle()
            shuffledIndices[shuffledIndices.indexOf(player.currentMediaItemIndex)] = shuffledIndices[0]
            shuffledIndices[0] = player.currentMediaItemIndex
            player.setShuffleOrder(DefaultShuffleOrder(shuffledIndices, System.currentTimeMillis()))
        }
    }

    override fun onPlayerError( error: PlaybackException ) {
        if( tryRecoverRejectedStream( error ) ) return

        val rootCause = traverseErrorStack( error )

        when( rootCause ) {
            is PlayableFormatNotFoundException -> context.getString( R.string.error_couldn_t_find_a_playable_audio_format )
            is NoInternetException -> context.getString( R.string.no_connection )
            is MissingDecipherKeyException -> context.getString( R.string.error_failed_to_decipher_signature )

            else -> rootCause.message ?: context.getString( R.string.error_unknown )
        }.also( ::printErrorMessage )

        // TODO: Add additional recovery step if type of error allows it

        if ( Preferences.PLAYBACK_SKIP_ON_ERROR.value && player.hasNextMediaItem() )
            player.playNext()
    }

    override fun onEvents(player: Player, events: Player.Events) {
        if (
            events.containsAny(
                Player.EVENT_PLAYBACK_STATE_CHANGED,
                Player.EVENT_PLAY_WHEN_READY_CHANGED
            )
        ) {
            val isBufferingOrReady =
                player.playbackState == Player.STATE_BUFFERING || player.playbackState == Player.STATE_READY
            if (isBufferingOrReady && player.playWhenReady) {
                sendOpenEqualizerIntent()
            } else {
                sendCloseEqualizerIntent()
                if (!player.playWhenReady) {
                    waitingForNetwork.value = false
                }
            }
        }
    }
}

private const val MAX_STREAM_RETRIES = 2
