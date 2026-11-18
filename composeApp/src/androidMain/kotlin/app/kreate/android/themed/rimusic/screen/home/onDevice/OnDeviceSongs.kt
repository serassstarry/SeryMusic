package app.kreate.android.themed.rimusic.screen.home.onDevice

import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.Icons
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastMap
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavController
import app.kreate.android.LocalBottomMenu
import app.kreate.android.Preferences
import app.kreate.android.R
import app.kreate.android.constant.MenuPage
import app.kreate.android.local.LocalFolders
import app.kreate.android.service.player.StatefulPlayer
import app.kreate.android.themed.common.component.BottomMenu
import app.kreate.android.themed.rimusic.component.ItemSelector
import app.kreate.android.themed.rimusic.component.Search
import app.kreate.android.themed.rimusic.component.song.SongItem
import app.kreate.android.utils.shallowCompare
import app.kreate.database.models.Song
import it.fast4x.rimusic.colorPalette
import it.fast4x.rimusic.typography
import it.fast4x.rimusic.ui.components.SwipeablePlaylistItem
import it.fast4x.rimusic.ui.components.tab.toolbar.Button
import it.fast4x.rimusic.ui.styling.Dimensions
import it.fast4x.rimusic.ui.styling.LocalAppearance
import it.fast4x.rimusic.utils.addNext
import it.fast4x.rimusic.utils.asMediaItem
import it.fast4x.rimusic.utils.bold
import it.fast4x.rimusic.utils.enqueue
import it.fast4x.rimusic.utils.forcePlayAtIndex
import me.knighthat.component.FolderItem
import me.knighthat.utils.Toaster
import org.koin.compose.koinInject

/**
 * Music on the phone, browsed folder by folder. Folders are picked with Android's own
 * folder picker (any folder, SD card included), so no path has to be typed and songs
 * show up even when Android's music index doesn't list them.
 *
 * Hold a picked folder to remove it from the list.
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

    /** Folders opened, from the picked one to the current one; empty shows the picked folders */
    val stack = remember { mutableStateListOf<LocalFolders.Entry>() }
    var roots by remember { mutableStateOf( emptyList<LocalFolders.Entry>() ) }
    var subfolders by remember { mutableStateOf( emptyList<LocalFolders.Entry>() ) }
    var songs by remember { mutableStateOf( emptyList<Song>() ) }
    var isLoading by remember { mutableStateOf( false ) }

    val picker = rememberLauncherForActivityResult( ActivityResultContracts.OpenDocumentTree() ) { tree: Uri? ->
        tree ?: return@rememberLauncherForActivityResult
        LocalFolders.add( context, tree )
    }
    fun pickFolder() = runCatching { picker.launch( null ) }.onFailure { it.message?.let( Toaster::e ) }

    LaunchedEffect( savedFolders ) {
        roots = LocalFolders.roots( context )
        if( stack.isNotEmpty() && roots.none { it.treeUri == stack.first().treeUri } )
            stack.clear()
    }

    // Load what's inside the current folder: subfolders right away, songs as their details are read
    LaunchedEffect( stack.toList() ) {
        val folder = stack.lastOrNull()
        subfolders = emptyList()
        songs = emptyList()
        if( folder == null ) return@LaunchedEffect

        isLoading = true
        val entries = LocalFolders.children( context, folder )
        subfolders = entries.filter { it.isFolder }
        val loaded = mutableListOf<Song>()
        entries.filter { !it.isFolder }.forEach { file ->
            loaded += LocalFolders.songOf( context, file )
            songs = loaded.toList()
        }
        isLoading = false
        lazyListState.scrollToItem( 0 )
    }

    LaunchedEffect( songs, search.input ) {
        songs.filter { !parentalControlEnabled || !it.isExplicit }
             .filter { search appearsIn it.cleanTitle() || search appearsIn it.cleanArtistsText() }
             .let {
                 itemsOnDisplay.clear()
                 itemsOnDisplay.addAll( it )
             }
    }

    BackHandler( enabled = stack.isNotEmpty() ) { stack.removeAt( stack.lastIndex ) }

    val currentMediaItem by player.currentMediaItemState.collectAsState()
    val songItemValues = remember( colorPalette, typography ) {
        SongItem.Values.from( colorPalette, typography )
    }

    LazyColumn(
        state = lazyListState,
        contentPadding = PaddingValues( bottom = Dimensions.bottomSpacer )
    ) {
        if( stack.isEmpty() ) {
            if( roots.isEmpty() )
                item( "empty" ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth().padding( horizontal = 32.dp, vertical = 48.dp )
                    ) {
                        Icon(
                            painter = painterResource( R.drawable.folder ),
                            contentDescription = null,
                            tint = colorPalette().textDisabled,
                            modifier = Modifier.size( 72.dp )
                        )
                        Spacer( Modifier.height(16.dp) )
                        BasicText(
                            text = stringResource( R.string.local_folders_empty ),
                            style = typography().s.copy( color = colorPalette().textSecondary )
                        )
                        Spacer( Modifier.height(24.dp) )
                        Button(
                            onClick = ::pickFolder,
                            border = BorderStroke( 2.dp, colorPalette().accent ),
                            colors = ButtonDefaults.buttonColors().copy( containerColor = Color.Transparent )
                        ) {
                            BasicText(
                                text = stringResource( R.string.local_folders_choose ),
                                style = typography().m.bold.copy( color = colorPalette().accent )
                            )
                        }
                    }
                }
            else {
                items( roots, key = { it.treeUri.toString() } ) { root ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy( 12.dp ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip( RoundedCornerShape(10.dp) )
                            .combinedClickable(
                                onClick = { stack.add( root ) },
                                onLongClick = {
                                    LocalFolders.remove( context, root.treeUri )
                                    Toaster.i( R.string.local_folders_removed )
                                }
                            )
                            .padding( horizontal = 16.dp, vertical = Dimensions.itemsVerticalPadding )
                    ) {
                        Icon(
                            painter = painterResource( R.drawable.folder ),
                            contentDescription = null,
                            tint = colorPalette().text,
                            modifier = Modifier.size( Dimensions.thumbnails.song )
                        )
                        BasicText(
                            text = root.name,
                            style = typography().m.copy( color = colorPalette().text )
                        )
                    }
                }
                item( "add" ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy( 12.dp ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip( RoundedCornerShape(10.dp) )
                            .combinedClickable( onClick = ::pickFolder )
                            .padding( horizontal = 16.dp, vertical = Dimensions.itemsVerticalPadding )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            tint = colorPalette().accent,
                            modifier = Modifier.size( Dimensions.thumbnails.song )
                        )
                        BasicText(
                            text = stringResource( R.string.local_folders_add ),
                            style = typography().m.copy( color = colorPalette().accent )
                        )
                    }
                }
            }
            return@LazyColumn
        }

        // Where we are, tap to go back up
        item( "path" ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable { stack.removeAt( stack.lastIndex ) }
                    .padding( horizontal = 16.dp, vertical = 10.dp )
            ) {
                Icon(
                    painter = painterResource( R.drawable.chevron_back ),
                    contentDescription = null,
                    tint = colorPalette().accent,
                    modifier = Modifier.size( 20.dp )
                )
                Spacer( Modifier.size(8.dp) )
                BasicText(
                    text = stack.joinToString( " / " ) { it.name },
                    style = typography().s.bold.copy( color = colorPalette().text ),
                    maxLines = 1
                )
            }
        }

        items( subfolders, key = { "dir:" + it.documentId } ) { folder ->
            FolderItem( folder.name ) { stack.add( folder ) }
        }

        if( !isLoading && subfolders.isEmpty() && songs.isEmpty() )
            item( "nothing" ) {
                BasicText(
                    text = stringResource( R.string.local_folders_no_music ),
                    style = typography().s.copy( color = colorPalette().textDisabled ),
                    modifier = Modifier.padding( 24.dp )
                )
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
    }
}
