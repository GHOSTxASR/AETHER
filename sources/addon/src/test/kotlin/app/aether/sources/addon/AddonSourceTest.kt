package app.aether.sources.addon

import app.aether.core.model.MediaId
import app.aether.core.model.Outcome
import app.aether.core.model.SourceError
import app.aether.core.model.Track
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** An addon as AETHER asks it, against a scripted one: what BitChord's host does, AETHER does. */
class AddonSourceTest {
    private class Addon(var manifest: String = MANIFEST) {
        val asked = mutableListOf<String>()
        var search: Pair<Int, String> = 200 to """{"tracks":[{"id":"t1","title":"Hello","artist":"Adele","duration":295,"artworkURL":"https://img/x.jpg","streamURL":"https://cdn/row.flac"}]}"""
        var stream: Pair<Int, String> = 200 to """{"url":"https://cdn/asked.flac","codec":"flac","sampleRate":44100,"bitDepth":16}"""
        var dropFirst = 0
        /** Anything else the addon serves, by path: playlists, for the open-media checks. */
        val files = mutableMapOf<String, String>()

        val http: OkHttpClient = OkHttpClient.Builder().addInterceptor { chain ->
            val url = chain.request().url
            asked += url.encodedPath
            if (dropFirst > 0) { dropFirst--; throw IOException("SocketTimeoutException") }
            val (code, body) = when {
                url.encodedPath.endsWith("/manifest.json") -> 200 to manifest
                url.encodedPath.endsWith("/search") -> search
                url.encodedPath.contains("/stream/") -> stream
                else -> files[url.encodedPath]?.let { 200 to it } ?: (404 to "{}")
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("x")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
    }

    private fun source(addon: Addon, lossless: Boolean = true): AddonSource = runBlocking {
        (AddonSource.connect("https://addon.example/a/manifest.json", addon.http, losslessOutput = { lossless }) as Outcome.Ok).value
    }

    private fun AddonSource.found(): List<Track> = runBlocking { (search("Hello Adele", null) as Outcome.Ok).value.items.filterIsInstance<Track>() }

    @Test fun `a refusal is kept in the addon's own words, and repeated ones rest it`() = runBlocking {
        val addon = Addon().apply { search = 403 to """{"error":"Qobuz upstream returned a non-JSON response."}""" }
        val s = source(addon)
        assertIs<Outcome.Err>(s.search("Hello", null))
        assertEquals("Qobuz upstream returned a non-JSON response.", s.lastError)
        assertNull(s.trouble)
        s.search("Hello", null)
        assertEquals("Qobuz upstream returned a non-JSON response.", s.trouble)
        assertFalse(s.ready)
    }

    @Test fun `an answer clears the last error`() = runBlocking {
        val addon = Addon().apply { search = 500 to """{"message":"down for a minute"}""" }
        val s = source(addon)
        s.search("Hello", null)
        assertEquals("down for a minute", s.lastError)
        addon.search = 200 to """{"tracks":[]}"""
        s.search("Hello", null)
        assertNull(s.lastError)
    }

    @Test fun `stream is asked first, for what it says about the address`() = runBlocking {
        val addon = Addon()
        val s = source(addon)
        val row = s.found().single()
        val stream = (s.stream(row.id, false) as Outcome.Ok).value
        assertEquals("https://cdn/asked.flac", stream.url)
        assertEquals(44100, stream.sampleRateHz)
        assertTrue(addon.asked.any { it.contains("/stream/t1") })
    }

    @Test fun `the row's own address is the fallback when stream has nothing`() = runBlocking {
        val addon = Addon().apply { stream = 404 to "{}" }
        val s = source(addon)
        val row = s.found().single()
        assertEquals("https://cdn/row.flac", (s.stream(row.id, false) as Outcome.Ok).value.url)
    }

    @Test fun `the row's own address is the fallback when stream gives one that isn't on the web`() = runBlocking {
        val addon = Addon().apply { stream = 200 to """{"url":"file:///data/data/app.aether/files/x.flac"}""" }
        val s = source(addon)
        val row = s.found().single()
        assertEquals("https://cdn/row.flac", (s.stream(row.id, false) as Outcome.Ok).value.url)
    }

    @Test fun `an addon that only searches plays from its rows`() = runBlocking {
        val addon = Addon("""{"id":"x","name":"Rows","resources":["search"]}""")
        val s = source(addon)
        val row = s.found().single()
        assertEquals("https://cdn/row.flac", (s.stream(row.id, false) as Outcome.Ok).value.url)
        assertFalse(addon.asked.any { it.contains("/stream/") })
    }

    @Test fun `one that asks for a lossless output isn't asked without one`() = runBlocking {
        val addon = Addon("""{"id":"q","name":"Qobuz","resources":["search","stream"],"checkValidLossless":1}""")
        val waiting = source(addon, lossless = false)
        assertTrue(waiting.manifest.requiresLosslessOutput)
        assertFalse(waiting.ready)
        val miss = waiting.search("Hello", null)
        assertIs<Outcome.Err>(miss)
        assertIs<SourceError.NotPlayable>(miss.error)
        assertNull(waiting.exactCopy(Track(MediaId("ytm:x"), "Hello", emptyList())))
        assertFalse(addon.asked.any { it.endsWith("/search") })
        val connected = source(addon, lossless = true)
        assertTrue(connected.ready)
        assertEquals(1, connected.found().size)
    }

    @Test fun `a row's cover is kept`() {
        val row = source(Addon()).found().single()
        assertEquals("https://img/x.jpg", row.artwork.url(600))
    }

    @Test fun `adding is asked again when the network let it down`() = runBlocking {
        val addon = Addon().apply { dropFirst = 1 }
        val r = AddonSource.connect("https://addon.example/a/manifest.json", addon.http)
        assertIs<Outcome.Ok<AddonSource>>(r)
        assertEquals(2, addon.asked.count { it.endsWith("/manifest.json") })
    }

    @Test fun `a refusal's words are read from either spelling`() {
        assertEquals("upstream down", AddonProtocol.errorText("""{"error":"upstream  down"}""".toByteArray()))
        assertEquals("nested", AddonProtocol.errorText("""{"error":{"message":"nested"}}""".toByteArray()))
        assertNull(AddonProtocol.errorText("<html>no</html>".toByteArray()))
        assertNotNull(AddonProtocol.errorText("""{"detail":"why"}""".toByteArray()))
    }

    private fun hls(addon: Addon): Outcome<app.aether.core.model.Stream> {
        addon.stream = 200 to """{"url":"https://cdn.example/hls/master.m3u8","manifest":"hls"}"""
        val s = source(addon)
        return runBlocking { s.stream(s.found().single().id, false) }
    }

    @Test fun `an HLS master with any keyed variant is refused, not only when the first one is`() {
        val addon = Addon()
        addon.files["/hls/master.m3u8"] = m3u8("#EXT-X-STREAM-INF:BANDWIDTH=128000", "low.m3u8", "#EXT-X-STREAM-INF:BANDWIDTH=320000", "high.m3u8")
        addon.files["/hls/low.m3u8"] = m3u8("#EXTINF:10,", "a.aac")
        addon.files["/hls/high.m3u8"] = m3u8("""#EXT-X-KEY:METHOD=AES-128,URI="https://keys.example/k"""", "#EXTINF:10,", "a.aac")
        assertIs<Outcome.Err>(hls(addon))
        assertTrue(addon.asked.contains("/hls/high.m3u8"))
    }

    @Test fun `an HLS master's keyed audio rendition is refused`() {
        val addon = Addon()
        addon.files["/hls/master.m3u8"] = m3u8(
            """#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="a",NAME="Main",URI="audio.m3u8"""",
            """#EXT-X-STREAM-INF:BANDWIDTH=320000,AUDIO="a"""",
            "video.m3u8",
        )
        addon.files["/hls/video.m3u8"] = m3u8("#EXTINF:10,", "v.ts")
        addon.files["/hls/audio.m3u8"] = m3u8("""#EXT-X-KEY:METHOD=SAMPLE-AES,URI="skd://k"""", "#EXTINF:10,", "a.aac")
        assertIs<Outcome.Err>(hls(addon))
    }

    @Test fun `an open HLS master plays, every variant read`() {
        val addon = Addon()
        addon.files["/hls/master.m3u8"] = m3u8("#EXT-X-STREAM-INF:BANDWIDTH=128000", "low.m3u8", "#EXT-X-STREAM-INF:BANDWIDTH=320000", "high.m3u8")
        addon.files["/hls/low.m3u8"] = m3u8("#EXTINF:10,", "a.aac")
        addon.files["/hls/high.m3u8"] = m3u8("#EXT-X-KEY:METHOD=NONE", "#EXTINF:10,", "a.aac")
        assertIs<Outcome.Ok<*>>(hls(addon))
        assertTrue(addon.asked.containsAll(listOf("/hls/low.m3u8", "/hls/high.m3u8")))
    }

    @Test fun `an open HLS media playlist plays without its segments being fetched`() {
        val addon = Addon()
        addon.files["/hls/master.m3u8"] = m3u8("#EXT-X-TARGETDURATION:10", "#EXTINF:10,", "segment0.aac")
        assertIs<Outcome.Ok<*>>(hls(addon))
        assertFalse(addon.asked.any { it.endsWith(".aac") })
    }

    private fun m3u8(vararg lines: String) = (listOf("#EXTM3U") + lines).joinToString("\n", postfix = "\n")

    private companion object {
        const val MANIFEST = """{"id":"dev.test","name":"Test","resources":["search","stream"],"settings":[{"key":"quality","default":"LOSSLESS","options":[{"value":"LOSSLESS"},{"value":"HIGH"}]}]}"""
    }
}
