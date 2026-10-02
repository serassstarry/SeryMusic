package app.kreate.constant

import app.kreate.component.Drawable
import app.kreate.component.TextView
import com.serymusic.app.generated.resources.Res
import com.serymusic.app.generated.resources.album
import com.serymusic.app.generated.resources.artist
import com.serymusic.app.generated.resources.autoplay
import com.serymusic.app.generated.resources.bar_chart
import com.serymusic.app.generated.resources.calendar
import com.serymusic.app.generated.resources.clock_loader
import com.serymusic.app.generated.resources.cross_shuffle
import com.serymusic.app.generated.resources.heart
import com.serymusic.app.generated.resources.hourglass_arrow_up
import com.serymusic.app.generated.resources.position
import com.serymusic.app.generated.resources.recent_actors
import com.serymusic.app.generated.resources.sort_album_and_artist
import com.serymusic.app.generated.resources.sort_album_title
import com.serymusic.app.generated.resources.sort_album_year
import com.serymusic.app.generated.resources.sort_artist
import com.serymusic.app.generated.resources.sort_date_added
import com.serymusic.app.generated.resources.sort_date_liked
import com.serymusic.app.generated.resources.sort_date_played
import com.serymusic.app.generated.resources.sort_listening_time
import com.serymusic.app.generated.resources.sort_position
import com.serymusic.app.generated.resources.sort_random
import com.serymusic.app.generated.resources.sort_relative_listening_time
import com.serymusic.app.generated.resources.sort_song_duration
import com.serymusic.app.generated.resources.sort_title
import com.serymusic.app.generated.resources.title
import com.serymusic.app.generated.resources.year
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource


enum class PlaylistSongSortBy(
    override val iconId: DrawableResource,
    override val textId: StringResource,
    override val isRandom: Boolean = false
): Drawable, TextView, SortCategory {

    RANDOM(Res.drawable.cross_shuffle, Res.string.sort_random, true),

    ALBUM(Res.drawable.album, Res.string.sort_album_title),

    ALBUM_YEAR(Res.drawable.year, Res.string.sort_album_year),

    ARTIST(Res.drawable.artist, Res.string.sort_artist),

    ARTIST_AND_ALBUM(Res.drawable.recent_actors, Res.string.sort_album_and_artist),

    DATE_PLAYED(Res.drawable.autoplay, Res.string.sort_date_played),

    TOTAL_PLAY_TIME(Res.drawable.hourglass_arrow_up, Res.string.sort_listening_time),

    RELATIVE_PLAY_TIME(Res.drawable.bar_chart, Res.string.sort_relative_listening_time),

    POSITION(Res.drawable.position, Res.string.sort_position),

    TITLE(Res.drawable.title, Res.string.sort_title),

    DURATION(Res.drawable.clock_loader, Res.string.sort_song_duration),

    DATE_LIKED(Res.drawable.heart, Res.string.sort_date_liked),

    DATE_ADDED(Res.drawable.calendar, Res.string.sort_date_added);
}