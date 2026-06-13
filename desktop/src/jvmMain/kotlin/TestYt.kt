import com.metrolist.innertube.YouTube
import kotlinx.coroutines.runBlocking

fun main(args: Array<String>) {
    runBlocking {
        val r = YouTube.home()
        if (r.isSuccess) {
            println("HOME_SUCCESS: ${r.getOrNull()?.sections?.size} sections found")
            r.getOrNull()?.sections?.forEach {
                println("Section: ${it.title}, items: ${it.items.size}")
            }
        } else {
            println("HOME_FAILURE:")
            r.exceptionOrNull()?.printStackTrace()
        }
    }
}
