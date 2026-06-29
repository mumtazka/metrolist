// BIG NOTE: this is the last known working desktop test version.
// Keep it as the baseline until the NewPipe fetch issue is fully fixed.
import com.metrolist.innertube.NewPipeExtractor
import kotlinx.coroutines.runBlocking
import java.net.HttpURLConnection
import java.net.URL

fun main(args: Array<String>) {
    runBlocking {
        val videoId = "pz8lYpPp2VE" // Life Goes On
        println("Calling NewPipeExtractor.newPipePlayer...")
        val streams = NewPipeExtractor.newPipePlayer(videoId)
        if (streams.isEmpty()) {
            println("NewPipeExtractor returned NO streams")
            return@runBlocking
        }
        println("NewPipeExtractor returned ${streams.size} streams:")
        for ((itag, url) in streams) {
            println("itag: $itag, url: $url")
        }

        val url = streams.first().second
        println("\nTesting first stream URL with Web User-Agent...")
        try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            connection.connect()
            val code = connection.responseCode
            println("Response Code: $code")
            if (code == 200 || code == 206) {
                println("SUCCESS!")
            } else {
                println("Failed: ${connection.responseMessage}")
            }
            connection.disconnect()
        } catch (e: Exception) {
            println("Error: ${e.message}")
        }
    }
}
