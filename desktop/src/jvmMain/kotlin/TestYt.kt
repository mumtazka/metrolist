import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.YouTubeClient
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    val r = YouTube.player("MiAoetOXKcY", client = YouTubeClient.IOS)
    val url = r.getOrNull()?.streamingData?.adaptiveFormats?.filter { it.isAudio && it.url != null }?.maxByOrNull { it.bitrate }?.url
    println("STREAM_URL_START\n$url\nSTREAM_URL_END")
}
