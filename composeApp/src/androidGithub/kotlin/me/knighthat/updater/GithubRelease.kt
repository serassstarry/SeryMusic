package me.knighthat.updater

import android.content.Context
import android.text.format.Formatter
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.koin.java.KoinJavaComponent.inject
import kotlin.time.ExperimentalTime
import kotlin.time.Instant


@Serializable
data class GithubRelease(
    val id: UInt = 0u,

    val name: String = "",

    val prerelease: Boolean = false,

    @SerialName("tag_name")
    val tagName: String,

    @SerialName("assets")
    val builds: List<Build>,

    @OptIn(ExperimentalTime::class)
    @SerialName("published_at")
    val publishedAt: Instant = Instant.DISTANT_PAST
) {

    @Serializable
    data class Build(
        val id: UInt = 0u,

        val url: String = "",

        val name: String,

        val size: UInt,

        val digest: String?,

        @SerialName("created_at")
        val createdAt: String = "",

        @SerialName("browser_download_url")
        val downloadUrl: String
    ) {

        val readableSize: String by lazy {
            val context: Context by inject(Context::class.java)
            Formatter.formatShortFileSize( context, this.size.toLong() )
        }
    }
}