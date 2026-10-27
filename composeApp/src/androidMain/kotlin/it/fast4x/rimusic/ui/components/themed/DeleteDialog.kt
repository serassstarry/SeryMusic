package it.fast4x.rimusic.ui.components.themed

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.ui.res.stringResource
import app.kreate.android.R
import it.fast4x.rimusic.ui.components.MenuState
import it.fast4x.rimusic.ui.components.tab.toolbar.ConfirmDialog
import it.fast4x.rimusic.ui.components.tab.toolbar.Descriptive
import it.fast4x.rimusic.ui.components.tab.toolbar.MenuIcon

abstract class DeleteDialog protected constructor(
    protected val activeState: MutableState<Boolean>,
    protected val menuState: MenuState
): ConfirmDialog, MenuIcon, Descriptive {

    override val iconId: Int = R.drawable.trash
    override val messageId: Int = R.string.delete
    override val menuIconTitle: String
        @Composable
        get() = stringResource( messageId )

    // Menus create a new instance of this dialog on every recomposition, so a field
    // copied from [activeState] goes stale and "Delete" never showed its dialog.
    // Reading the state itself keeps every instance in sync and lets Compose observe it.
    override var isActive: Boolean
        get() = activeState.value
        set(value) {
            activeState.value = value
        }

    override fun onShortClick() = super.onShortClick()

    override fun onDismiss() {
        super.onDismiss()
        menuState.hide()
    }
}