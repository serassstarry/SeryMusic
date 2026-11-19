package app.kreate.android.local

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.net.toUri
import app.kreate.android.Preferences
import app.kreate.database.models.Song
import it.fast4x.rimusic.Database
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

/**
 * Music folders the user picked with Android's folder picker. Files are read through
 * the picker's permission, so they show up even when Android's music index (MediaStore)
 * doesn't list them, and no storage permission is needed.
 */
object LocalFolders {

    private val AUDIO_EXTENSIONS = setOf( "mp3", "m4a", "aac", "ogg", "oga", "opus", "flac", "wav", "wma", "mka", "webm", "amr", "3gp" )

    /** A folder or a song file inside a picked folder */
    data class Entry(
        val treeUri: Uri,
        val documentId: String,
        val name: String,
        val isFolder: Boolean
    ) {
        val uri: Uri get() = DocumentsContract.buildDocumentUriUsingTree( treeUri, documentId )
    }

    /** Picked folders, as entries to start browsing from */
    fun roots( context: Context ): List<Entry> =
        Preferences.LOCAL_MUSIC_FOLDERS.value
                   .lines()
                   .filter( String::isNotBlank )
                   .map( String::toUri )
                   .filter { tree -> context.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission } }
                   .map { tree ->
                       val id = DocumentsContract.getTreeDocumentId( tree )
                       Entry( tree, id, displayName(context, tree, id) ?: id.substringAfterLast(':').ifBlank { id }, true )
                   }

    fun add( context: Context, tree: Uri ) {
        runCatching {
            context.contentResolver.takePersistableUriPermission( tree, Intent.FLAG_GRANT_READ_URI_PERMISSION )
        }
        val saved = Preferences.LOCAL_MUSIC_FOLDERS.value.lines().filter( String::isNotBlank )
        if( tree.toString() !in saved )
            Preferences.LOCAL_MUSIC_FOLDERS.value = (saved + tree.toString()).joinToString( "\n" )
    }

    fun remove( context: Context, tree: Uri ) {
        runCatching {
            context.contentResolver.releasePersistableUriPermission( tree, Intent.FLAG_GRANT_READ_URI_PERMISSION )
        }
        Preferences.LOCAL_MUSIC_FOLDERS.value =
            Preferences.LOCAL_MUSIC_FOLDERS.value.lines().filter { it.isNotBlank() && it != tree.toString() }.joinToString( "\n" )
    }

    private fun displayName( context: Context, tree: Uri, documentId: String ): String? = runCatching {
        context.contentResolver.query(
            DocumentsContract.buildDocumentUriUsingTree( tree, documentId ),
            arrayOf( DocumentsContract.Document.COLUMN_DISPLAY_NAME ), null, null, null
        )?.use { if( it.moveToFirst() ) it.getString( 0 ) else null }
    }.getOrNull()

    private fun isAudio( name: String, mime: String? ): Boolean =
        mime?.startsWith( "audio/" ) == true
                || name.substringAfterLast( '.', "" ).lowercase() in AUDIO_EXTENSIONS

    /**
     * Subfolders first, then songs, both sorted by name.
     */
    suspend fun children( context: Context, folder: Entry ): List<Entry> = withContext( Dispatchers.IO ) {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree( folder.treeUri, folder.documentId )
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        val results = mutableListOf<Entry>()
        runCatching {
            context.contentResolver.query( uri, columns, null, null, null )?.use { cursor ->
                while( cursor.moveToNext() ) {
                    val id = cursor.getString( 0 ) ?: continue
                    val name = cursor.getString( 1 ) ?: continue
                    val mime = cursor.getString( 2 )
                    if( name.startsWith( "." ) ) continue

                    when {
                        mime == DocumentsContract.Document.MIME_TYPE_DIR -> results += Entry( folder.treeUri, id, name, true )
                        isAudio( name, mime ) -> results += Entry( folder.treeUri, id, name, false )
                    }
                }
            }
        }
        results.sortedWith( compareBy<Entry>( { !it.isFolder }, { it.name.lowercase() } ) )
    }

    /**
     * Song for [file]: title, artist, duration and embedded cover read from the file the
     * first time, then kept in the database so likes, history and playlists work.
     */
    suspend fun songOf( context: Context, file: Entry ): Song = withContext( Dispatchers.IO ) {
        val id = file.uri.toString()
        Database.songTable.findById( id ).first()?.let { return@withContext it }

        val retriever = MediaMetadataRetriever()
        val song = try {
            retriever.setDataSource( context, file.uri )
            val title = retriever.extractMetadata( MediaMetadataRetriever.METADATA_KEY_TITLE )
            val artist = retriever.extractMetadata( MediaMetadataRetriever.METADATA_KEY_ARTIST )
                ?: retriever.extractMetadata( MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST )
            val duration = retriever.extractMetadata( MediaMetadataRetriever.METADATA_KEY_DURATION )?.toLongOrNull()
            val cover = retriever.embeddedPicture?.let { bytes ->
                val dir = File( context.cacheDir, "local_covers" ).apply { mkdirs() }
                File( dir, "${id.hashCode()}.jpg" ).apply { writeBytes( bytes ) }.toUri().toString()
            }
            Song(
                id = id,
                title = title?.takeIf( String::isNotBlank ) ?: file.name.substringBeforeLast( '.' ),
                artistsText = artist,
                durationText = duration?.let( ::formatDuration ),
                thumbnailUrl = cover,
                isLocal = true
            )
        } catch( _: Exception ) {
            Song( id, file.name.substringBeforeLast( '.' ), null, null, null, isLocal = true )
        } finally {
            runCatching { retriever.release() }
        }

        Database.asyncTransaction { songTable.insertIgnore( song ) }
        song
    }

    /** Songs of all picked folders, filled in while [scan] runs */
    val songs = kotlinx.coroutines.flow.MutableStateFlow( emptyList<Song>() )
    val isScanning = kotlinx.coroutines.flow.MutableStateFlow( false )
    private val scanLock = kotlinx.coroutines.sync.Mutex()

    /**
     * Reads every picked folder and its subfolders. Songs already known come from the
     * database, so only new files are opened; the list updates as songs are found.
     */
    suspend fun scan( context: Context ) = scanLock.withLock {
        isScanning.value = true
        try {
            val found = mutableListOf<Song>()
            suspend fun walk( folder: Entry, depth: Int ) {
                if( depth > 12 ) return
                val entries = children( context, folder )
                entries.filter { !it.isFolder }.forEach { file ->
                    found += songOf( context, file )
                    if( found.size % 10 == 0 ) songs.value = found.sortedBy { it.title.lowercase() }
                }
                entries.filter { it.isFolder }.forEach { walk( it, depth + 1 ) }
            }
            roots( context ).forEach { walk( it, 0 ) }
            songs.value = found.sortedBy { it.title.lowercase() }
        } finally {
            isScanning.value = false
        }
    }

    private fun formatDuration( ms: Long ): String =
        ms.milliseconds.toComponents { hrs, mins, secs, _ ->
            if( hrs > 0 ) "%02d:%02d:%02d".format( hrs, mins, secs ) else "%02d:%02d".format( mins, secs )
        }
}
