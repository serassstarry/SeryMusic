package app.kreate.android.tv

import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager

/**
 * Text fields keep the up and down arrows to move their cursor, so with a TV
 * remote there was no way to leave them. Put on the root of a screen or dialog,
 * this moves focus up/down first, and only lets the arrow through when there's
 * nothing to move to.
 */
fun Modifier.tvVerticalFocus( enabled: Boolean ): Modifier =
    if( !enabled ) this
    else composed {
        val focusManager = LocalFocusManager.current
        onPreviewKeyEvent { event ->
            if( event.type != KeyEventType.KeyDown ) return@onPreviewKeyEvent false
            when( event.key ) {
                Key.DirectionDown -> focusManager.moveFocus( FocusDirection.Down )
                Key.DirectionUp -> focusManager.moveFocus( FocusDirection.Up )
                else -> false
            }
        }
    }
