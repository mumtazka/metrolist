package com.metrolist.desktop.importer

import com.metrolist.innertube.YouTube
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.long
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import java.awt.Desktop
import java.net.HttpCookie
import java.net.InetSocketAddress
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import com.sun.net.httpserver.HttpServer

data class ExternalTrack(
    val title: String,
    val artist: String,
    val album: String?,
    val durationMs: Long?,
    val isrc: String?,
)

data class ExternalPlaylist(
    val name: String,
    val imageUrl: String?,
    val tracks: List<ExternalTrack>,
)

data class MatchedTrack(
    val source: ExternalTrack,
    val youtubeId: String?,
    val youtubeTitle: String? = null,
    val youtubeArtist: String? = null,
    val albumArt: String? = null,
    val durationMs: Long = 210_000L,
    val ambiguous: Boolean = false,
)

class SpotifyPlaylistImporter(
    private val clientId: String = SpotifyConfig.CLIENT_ID,
    private val redirectPort: Int = SpotifyConfig.REDIRECT_PORT,
) {
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    private val http = HttpClient(OkHttp) {
        install(ContentNegotiation) { json(json) }
        expectSuccess = true
    }

    suspend fun import(url: String): ExternalPlaylist = withContext(Dispatchers.IO) {
        require(clientId.isNotBlank()) { "Spotify Client ID belum dikonfigurasi." }
        val playlistId = Regex("(?:playlist[/:])([A-Za-z0-9]+)")
            .find(url.trim())?.groupValues?.get(1)
            ?: throw IllegalArgumentException("URL playlist Spotify tidak valid.")
        val token = authorize()
        val first = http.get("https://api.spotify.com/v1/playlists/$playlistId") {
            headers.append("Authorization", "Bearer $token")
        }.body<JsonObject>()
        val tracks = mutableListOf<ExternalTrack>()
        var next: String? = "https://api.spotify.com/v1/playlists/$playlistId/items?limit=100"
        while (next != null) {
            val page = http.get(next) { headers.append("Authorization", "Bearer $token") }.body<JsonObject>()
            page["items"]?.jsonArray?.forEach { entry ->
                val track = entry.jsonObject["item"]?.jsonObject ?: return@forEach
                if (track["type"]?.jsonPrimitive?.content != "track") return@forEach
                val artists = track["artists"]?.jsonArray.orEmpty()
                val artist = artists.firstOrNull()?.jsonObject?.get("name")?.jsonPrimitive?.content
                    ?: return@forEach
                tracks += ExternalTrack(
                    title = track["name"]?.jsonPrimitive?.content ?: return@forEach,
                    artist = artist,
                    album = track["album"]?.jsonObject?.get("name")?.jsonPrimitive?.content,
                    durationMs = track["duration_ms"]?.jsonPrimitive?.long,
                    isrc = track["external_ids"]?.jsonObject?.get("isrc")?.jsonPrimitive?.content,
                )
            }
            next = page["next"]?.jsonPrimitive?.content
        }
        ExternalPlaylist(
            name = first["name"]?.jsonPrimitive?.content ?: "Imported playlist",
            imageUrl = first["images"]?.jsonArray?.firstOrNull()?.jsonObject?.get("url")?.jsonPrimitive?.content,
            tracks = tracks,
        )
    }

    private suspend fun authorize(): String {
        val verifier = randomToken(64)
        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()),
        )
        val state = UUID.randomUUID().toString()
        val callback = CallbackServer(redirectPort, state)
        callback.start()
        val authUrl = URI(
            "https://accounts.spotify.com/authorize?client_id=${encode(clientId)}" +
                "&response_type=code&redirect_uri=${encode("http://127.0.0.1:$redirectPort/callback")}" +
                "&code_challenge_method=S256&code_challenge=${encode(challenge)}&state=${encode(state)}" +
                "&scope=${encode("playlist-read-private playlist-read-collaborative")}",
        )
        check(Desktop.isDesktopSupported()) { "Tidak dapat membuka browser untuk login Spotify." }
        Desktop.getDesktop().browse(authUrl)
        val code = try { callback.awaitCode() } finally { callback.stop() }
        return http.post("https://accounts.spotify.com/api/token") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(FormDataContent(Parameters.build {
                append("client_id", clientId)
                append("grant_type", "authorization_code")
                append("code", code)
                append("redirect_uri", "http://127.0.0.1:$redirectPort/callback")
                append("code_verifier", verifier)
            }))
        }.body<JsonObject>()["access_token"]?.jsonPrimitive?.content
            ?: error("Spotify tidak mengembalikan access token.")
    }

    private fun randomToken(size: Int): String = ByteArray(size).also { SecureRandom().nextBytes(it) }
        .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    private fun encode(value: String) = java.net.URLEncoder.encode(value, Charsets.UTF_8)
}

private class CallbackServer(private val port: Int, private val expectedState: String) {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
    @Volatile private var code: String? = null
    @Volatile private var error: String? = null

    init {
        server.createContext("/callback") { exchange ->
            val query = exchange.requestURI.rawQuery.orEmpty().split('&').mapNotNull {
                it.split('=', limit = 2).takeIf { pair -> pair.size == 2 }?.let { pair -> pair[0] to java.net.URLDecoder.decode(pair[1], "UTF-8") }
            }.toMap()
            if (query["state"] != expectedState) error = "OAuth state Spotify tidak cocok."
            else { code = query["code"]; error = query["error"] }
            val response = "Login selesai. Anda dapat menutup tab ini."
            exchange.sendResponseHeaders(200, response.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(response.toByteArray()) }
        }
    }

    fun start() = server.start()
    fun stop() = server.stop(0)
    fun awaitCode(): String {
        repeat(600) {
            error?.let { throw IllegalStateException("Login Spotify gagal: $it") }
            code?.let { return it }
            Thread.sleep(100)
        }
        error("Login Spotify timeout.")
    }
}
