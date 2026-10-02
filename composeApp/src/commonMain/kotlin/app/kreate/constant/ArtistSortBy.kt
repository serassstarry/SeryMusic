package app.kreate.constant

import app.kreate.component.Drawable
import app.kreate.component.TextView
import com.serymusic.app.generated.resources.Res
import com.serymusic.app.generated.resources.calendar
import com.serymusic.app.generated.resources.cross_shuffle
import com.serymusic.app.generated.resources.sort_date_added
import com.serymusic.app.generated.resources.sort_random
import com.serymusic.app.generated.resources.sort_title
import com.serymusic.app.generated.resources.title
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource


enum class ArtistSortBy(
    override val iconId: DrawableResource,
    override val textId: StringResource,
    override val isRandom: Boolean = false
): Drawable, TextView, SortCategory {

    RANDOM(Res.drawable.cross_shuffle, Res.string.sort_random, true),

    TITLE(Res.drawable.title, Res.string.sort_title),

    DATE_ADDED(Res.drawable.calendar, Res.string.sort_date_added);
}