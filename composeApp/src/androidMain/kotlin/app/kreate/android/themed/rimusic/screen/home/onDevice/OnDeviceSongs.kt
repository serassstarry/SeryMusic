package app.kreate.android.themed.rimusic.screen.home.onDevice

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastMap
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavController
import app.kreate.android.LocalBottomMenu
import app.kreate.android.Preferences
import app.kreate.android.R
import app.kreate.android.constant.MenuPage
import app.kreate.android.local.LocalFolders
import app.kreate.android.mascot.SeryMascot
import app.kreate.android.service.player.StatefulPlayer
import app.kreate.android.themed.common.component.BottomMenu
import app.kreate.android.themed.rimusic.component.ItemSelector
import app.kreate.android.themed.rimusic.component.Search
import app.kreate.android.themed.rimusic.component.song.SongItem
import app.kreate.android.utils.shallowCompare
import app.kreate.database.models.Song
import it.fast4x.rimusic.colorPalette
import it.fast4x.rimusic.enums.NavRoutes
import it.fast4x.rimusic.typography
import it.fast4x.rimusic.ui.components.SwipeablePlaylistItem
import it.fast4x.rimusic.ui.components.tab.toolbar.Button
import it.fast4x.rimusic.ui.components.themed.SecondaryTextButton
import it.fast4x.rimusic.ui.styling.Dimensions
import it.fast4x.rimusic.ui.styling.LocalAppearance
import it.fast4x.rimusic.utils.addNext
import it.fast4x.rimusic.utils.asMediaItem
import it.fast4x.rimusic.utils.enqueue
import it.fast4x.rimusic.utils.forcePlayAtIndex
import org.koin.compose.koinInject

/**
 * All songs of the music folders chosen in Settings (subfolders included), in one list.
 * Files are read through the folder picker's permission, so they show up even when
 * Android's music index doesn't list them.
 */
@UnstableApi
@ExperimentalFoundationApi
@Composable
fun OnDeviceSong(
    navController: NavController,
    lazyListState: LazyListState,
    itemSelector: ItemSelector<Song>,
    search: Search,
    buttons: MutableList<Button>,
    itemsOnDisplay: MutableList<Song>,
    getSongs: () -> List<Song>,
    menu: BottomMenu = LocalBottomMenu.current
) {
    val context = LocalContext.current
    val player: StatefulPlayer = koinInject()
    val (colorPalette, typography) = LocalAppearance.current
    val hapticFeedback = LocalHapticFeedback.current
    val parentalControlEnabled by Preferences.PARENTAL_CONTROL
    val savedFolders by Preferences.LOCAL_MUSIC_FOLDERS
    val songs by LocalFolders.songs.collectAsState()
    val isScanning by LocalFolders.isScanning.collectAsState()

    // Rescan whenever the page opens or the folders change; known songs show right away
    LaunchedEffect( savedFolders ) {
        if( savedFolders.isBlank() ) LocalFolders.songs.value = emptyList()
        else LocalFolders.scan( context )
    }

    LaunchedEffect( songs, search.input ) {
        songs.filter { !parentalControlEnabled || !it.isExplicit }
             .filter { search appearsIn it.cleanTitle() || search appearsIn it.cleanArtistsText() }
             .let {
                 itemsOnDisplay.clear()
                 itemsOnDisplay.addAll( it )
             }
    }

    val currentMediaItem by player.currentMediaItemState.collectAsState()
    val songItemValues = remember( colorPalette, typography ) {
        SongItem.Values.from( colorPalette, typography )
    }

    LazyColumn(
        state = lazyListState,
        contentPadding = PaddingValues( bottom = Dimensions.bottomSpacer )
    ) {
        if( songs.isEmpty() && !isScanning )
            item( "empty" ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth().padding( horizontal = 32.dp, vertical = 32.dp )
                ) {
                    SeryMascot( modifier = Modifier.size( 180.dp ) )
                    Spacer( Modifier.height(16.dp) )
                    BasicText(
                        text = stringResource(
                            if( savedFolders.isBlank() ) R.string.local_folders_empty else R.string.local_folders_no_music
                        ),
                        style = typography().s.copy( color = colorPalette().textSecondary, textAlign = TextAlign.Center )
                    )
                    Spacer( Modifier.height(20.dp) )
                    SecondaryTextButton(
                        text = stringResource( R.string.local_folders_open_settings ),
                        onClick = { NavRoutes.settings.navigateHere( navController ) }
                    )
                }
            }

        itemsIndexed(
            items = itemsOnDisplay,
            key = { _, song -> song.id }
        ) { index, song ->
            val mediaItem = song.asMediaItem

            SwipeablePlaylistItem(
                mediaItem = mediaItem,
                onPlayNext = { player.addNext( mediaItem ) },
                onEnqueue = { player.enqueue( mediaItem ) }
            ) {
                SongItem.Render(
                    song = song,
                    hapticFeedback = hapticFeedback,
                    isPlaying = song.shallowCompare( currentMediaItem ),
                    values = songItemValues,
                    itemSelector = itemSelector,
                    modifier = Modifier.animateItem(),
                    onClick = {
                        search.hideIfEmpty()
                        player.stopRadio()

                        val selectedSongs = getSongs()
                        if( song in selectedSongs )
                            player.forcePlayAtIndex(
                                selectedSongs.fastMap( Song::asMediaItem ),
                                selectedSongs.indexOf( song )
                            )
                        else
                            player.forcePlayAtIndex(
                                itemsOnDisplay.fastMap( Song::asMediaItem ),
                                index
                            )
                    },
                    onLongClick = {
                        menu.show( MenuPage.LocalSong( mediaItem ), true )
                    }
                )
            }
        }

        if( isScanning )
            item( "scanning" ) {
                BasicText(
                    text = stringResource( R.string.local_folders_scanning ),
                    style = typography().xs.copy( color = colorPalette().textDisabled ),
                    modifier = Modifier.padding( 20.dp )
                )
            }
    }
}
