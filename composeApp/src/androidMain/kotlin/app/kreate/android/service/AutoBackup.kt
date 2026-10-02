package app.kreate.android.service

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.net.toUri
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import app.kreate.android.BuildConfig
import app.kreate.android.Preferences
import co.touchlab.kermit.Logger
import it.fast4x.rimusic.Database
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.FileInputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.time.Duration.Companion.hours

/**
 * Copies the database into a folder picked by user (see [Preferences.AUTO_BACKUP_FOLDER]).
 *
 * The folder lives outside of app's private storage, so backups
 * are still there after the app is uninstalled.
 *
 * Backup runs when app goes to background, at most once every [INTERVAL],
 * because that's the moment new data (favorites, playlists, etc.) is settled.
 */
object AutoBackup : DefaultLifecycleObserver, KoinComponent {

    private const val MIME_TYPE = "application/vnd.sqlite3"
    private const val FILE_PREFIX = "${BuildConfig.APP_NAME}_autobackup_"
    private const val MAX_BACKUPS = 5
    private val INTERVAL = 24.hours

    private val context: Context by inject()
    private val logger = Logger.withTag( "AutoBackup" )
    private val lock = Mutex()

    val isEnabled: Boolean
        get() = Preferences.AUTO_BACKUP_FOLDER.value.isNotBlank()

    /**
     * Start backing up to [treeUri], previous folder (if any) is released.
     */
    fun enable( treeUri: Uri ) {
        disable()

        context.contentResolver.takePersistableUriPermission( treeUri, PERMISSION_FLAGS )
        Preferences.AUTO_BACKUP_FOLDER.value = treeUri.toString()
    }

    fun disable() {
        if( !isEnabled ) return

        runCatching {
            val treeUri = Preferences.AUTO_BACKUP_FOLDER.value.toUri()
            context.contentResolver.releasePersistableUriPermission( treeUri, PERMISSION_FLAGS )
        }
        Preferences.AUTO_BACKUP_FOLDER.value = ""
    }

    /**
     * @return human-readable name of the folder backups are stored in
     */
    fun folderName(): String =
        runCatching {
            val treeUri = Preferences.AUTO_BACKUP_FOLDER.value.toUri()
            // e.g. "primary:Documents/SeryMusic"
            DocumentsContract.getTreeDocumentId( treeUri ).substringAfter( ':' )
        }.getOrDefault( "" )

    /**
     * Make a backup right away, regardless of when the last one was made.
     */
    suspend fun backupNow(): Result<Unit> = withContext( Dispatchers.IO ) {
        lock.withLock {
            runCatching {
                check( isEnabled ) { "Backup folder isn't set" }

                val treeUri = Preferences.AUTO_BACKUP_FOLDER.value.toUri()
                val folderId = DocumentsContract.getTreeDocumentId( treeUri )
                val folderUri = DocumentsContract.buildDocumentUriUsingTree( treeUri, folderId )

                write( folderUri )
                Preferences.AUTO_BACKUP_LAST_RUN.value = System.currentTimeMillis()

                // Failing to remove old backups must not fail the backup itself
                runCatching { prune( treeUri, folderId ) }
                    .onFailure { logger.w( it ) { "Failed to remove old backups" } }

                Unit
            }.onFailure { logger.e( it ) { "Backup failed" } }
        }
    }

    private suspend fun write( folderUri: Uri ) {
        val resolver = context.contentResolver
        val timestamp = SimpleDateFormat( "yyyyMMdd_HHmmss", Locale.US ).format( Date() )
        val fileUri = DocumentsContract.createDocument( resolver, folderUri, MIME_TYPE, "$FILE_PREFIX$timestamp.sqlite" )
            ?: throw IOException("Can't create file in backup folder")

        try {
            // All commits must be written to base file before copying
            Database.checkpoint()

            val outStream = resolver.openOutputStream( fileUri )
                ?: throw IOException("Can't write to backup file")
            outStream.use {
                FileInputStream( context.getDatabasePath( Database.FILE_NAME ) ).use { inStream ->
                    inStream.copyTo( it )
                }
            }
        } catch( e: Exception ) {
            // Don't leave half-written file behind
            runCatching { DocumentsContract.deleteDocument( resolver, fileUri ) }
            throw e
        }
    }

    /**
     * Only keep [MAX_BACKUPS] most recent backups.
     */
    private fun prune( treeUri: Uri, folderId: String ) {
        val resolver = context.contentResolver
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree( treeUri, folderId )
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME
        )

        val backups = mutableListOf<Pair<String, String>>()
        resolver.query( childrenUri, projection, null, null, null )?.use { cursor ->
            while( cursor.moveToNext() ) {
                val name = cursor.getString( 1 ) ?: continue
                if( name.startsWith( FILE_PREFIX ) )
                    backups += name to cursor.getString( 0 )
            }
        }

        // Timestamp in file name makes alphabetical order the chronological order
        backups.sortedByDescending { it.first }
               .drop( MAX_BACKUPS )
               .forEach { (name, documentId) ->
                   val uri = DocumentsContract.buildDocumentUriUsingTree( treeUri, documentId )
                   DocumentsContract.deleteDocument( resolver, uri )

                   logger.d { "Removed old backup $name" }
               }
    }

    override fun onStop( owner: LifecycleOwner ) {
        if( !isEnabled ) return

        val elapsed = System.currentTimeMillis() - Preferences.AUTO_BACKUP_LAST_RUN.value
        if( elapsed < INTERVAL.inWholeMilliseconds ) return

        CoroutineScope( Dispatchers.IO ).launch { backupNow() }
    }

    private const val PERMISSION_FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
}
