package app.kreate.constant

import app.kreate.component.Drawable
import app.kreate.component.TextView
import com.serymusic.app.generated.resources.Res
import com.serymusic.app.generated.resources.artist
import com.serymusic.app.generated.resources.bookmark_stacks
import com.serymusic.app.generated.resources.calendar
import com.serymusic.app.generated.resources.clock_loader
import com.serymusic.app.generated.resources.cross_shuffle
import com.serymusic.app.generated.resources.sort_album_year
import com.serymusic.app.generated.resources.sort_artist
import com.serymusic.app.generated.resources.sort_date_added
import com.serymusic.app.generated.resources.sort_random
import com.serymusic.app.generated.resources.sort_songs_count
import com.serymusic.app.generated.resources.sort_title
import com.serymusic.app.generated.resources.sort_total_duration
import com.serymusic.app.generated.resources.title
import com.serymusic.app.generated.resources.year
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource


enum class AlbumSortBy(
    override val iconId: DrawableResource,
    override val textId: StringResource,
    override val isRandom: Boolean = false
): Drawable, TextView, SortCategory {

    RANDOM(Res.drawable.cross_shuffle, Res.string.sort_random, true),

    TITLE(Res.drawable.title, Res.string.sort_title),

    YEAR(Res.drawable.year, Res.string.sort_album_year),

    DATE_ADDED(Res.drawable.calendar, Res.string.sort_date_added),

    ARTIST(Res.drawable.artist, Res.string.sort_artist),

    SONGS_COUNT(Res.drawable.bookmark_stacks, Res.string.sort_songs_count),

    TOTAL_DURATION(Res.drawable.clock_loader, Res.string.sort_total_duration);
}