package app.kreate.android.themed.common.screens.settings

import it.fast4x.rimusic.typography
import androidx.compose.foundation.text.BasicText
import app.kreate.android.local.LocalFolders
import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import app.kreate.android.Preferences
import app.kreate.android.R
import app.kreate.android.themed.common.component.settings.SettingComponents
import app.kreate.android.themed.common.component.settings.SettingEntrySearch
import app.kreate.android.themed.common.component.settings.animatedEntry
import app.kreate.android.themed.common.component.settings.entry
import app.kreate.android.themed.common.component.settings.header
import app.kreate.android.themed.common.screens.settings.other.debugSection
import it.fast4x.rimusic.colorPalette
import it.fast4x.rimusic.enums.NavigationBarPosition
import it.fast4x.rimusic.ui.screens.settings.StringListValueSelectorSettingsEntry
import it.fast4x.rimusic.ui.styling.Dimensions
import it.fast4x.rimusic.utils.isAtLeastAndroid10
import it.fast4x.rimusic.utils.isAtLeastAndroid6
import it.fast4x.rimusic.utils.isIgnoringBatteryOptimizations
import me.knighthat.component.dialog.InputDialogConstraints
import me.knighthat.utils.Toaster
import java.io.File

@Composable
fun OtherSettings( paddingValues: PaddingValues ) {
    val context = LocalContext.current
    val scrollState = rememberLazyListState()

    val search = remember {
        SettingEntrySearch( scrollState, R.string.tab_miscellaneous, R.drawable.equalizer )
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.background( colorPalette().background0 )
                           .padding( paddingValues )
                           .fillMaxHeight()
                           .fillMaxWidth(
                               if ( NavigationBarPosition.Right.isCurrent() )
                                   Dimensions.contentWidthRightBar
                               else
                                   1f
                           )
    ) {
        search.ToolBarButton()

        LazyColumn(
            state = scrollState,
            contentPadding = PaddingValues(bottom = Dimensions.bottomSpacer)
        ) {
            header( R.string.on_device )
            // Folders picked with Android's folder picker; their songs show in Songs › On device
            entry( search, R.string.local_folders_title ) {
                val folders by Preferences.LOCAL_MUSIC_FOLDERS
                val picker = rememberLauncherForActivityResult( ActivityResultContracts.OpenDocumentTree() ) { tree ->
                    if( tree != null ) LocalFolders.add( context, tree )
                }
                val roots = remember( folders ) { LocalFolders.roots( context ) }
                Column {
                    SettingComponents.Text(
                        title = stringResource( R.string.local_folders_title ),
                        subtitle = stringResource( R.string.local_folders_add ),
                        onClick = { runCatching { picker.launch( null ) } }
                    )
                    roots.forEach { root ->
                        SettingComponents.Text(
                            title = "📁  " + root.name,
                            subtitle = stringResource( R.string.local_folders_remove ),
                            onClick = {
                                LocalFolders.remove( context, root.treeUri )
                                Toaster.i( R.string.local_folders_removed )
                            },
                            trailingContent = {
                                BasicText(
                                    text = "✕",
                                    style = typography().l.copy( color = colorPalette().red )
                                )
                            }
                        )
                    }
                }
            }

            header( R.string.androidheadunit )
            entry( search, R.string.extra_space ) {
                SettingComponents.BooleanEntry(
                    Preferences.PLAYER_EXTRA_SPACE,
                    R.string.extra_space
                )
            }

            header(
                titleId = R.string.service_lifetime,
                subtitle = { stringResource( R.string.battery_optimizations_applied ) }
            )
            entry( search, R.string.keep_screen_on ) {
                SettingComponents.BooleanEntry(
                    Preferences.KEEP_SCREEN_ON,
                    R.string.keep_screen_on,
                    R.string.prevents_screen_timeout
                )
            }
            entry(
                search = search,
                titleId = R.string.ignore_battery_optimizations,
                additionalCheck = isAtLeastAndroid6
            ) {
                var isIgnoringBatteryOptimizations by remember {
                    mutableStateOf( context.isIgnoringBatteryOptimizations )
                }
                val activityResultLauncher =
                    rememberLauncherForActivityResult(
                        ActivityResultContracts.StartActivityForResult()
                    ) {
                        isIgnoringBatteryOptimizations = context.isIgnoringBatteryOptimizations
                    }
                val subtitle by remember { derivedStateOf {
                    if (isIgnoringBatteryOptimizations)
                        context.getString( R.string.already_unrestricted )
                    else
                        context.getString( R.string.disable_background_restrictions )
                }}

                SettingComponents.Text(
                    title = stringResource( R.string.ignore_battery_optimizations ),
                    subtitle = subtitle,
                    onClick = {
                        try {
                            activityResultLauncher.launch(
                                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                    data = "package:${context.packageName}".toUri()
                                }
                            )
                        } catch (e: ActivityNotFoundException) {
                            try {
                                activityResultLauncher.launch(
                                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                )
                            } catch (e: ActivityNotFoundException) {
                                Toaster.i( R.string.not_find_battery_optimization_settings )
                            }
                        }
                    }
                )

                // TODO: ADD this to comment of "ignore optimization"
                // SettingComponents.Description( R.string.is_android12 )
            }

            header( R.string.parental_control )
            entry( search, R.string.parental_control ) {
                SettingComponents.BooleanEntry(
                    Preferences.PARENTAL_CONTROL,
                    R.string.parental_control,
                    R.string.info_prevent_play_songs_with_age_limitation
                )
            }

            debugSection( search )
        }
    }
}