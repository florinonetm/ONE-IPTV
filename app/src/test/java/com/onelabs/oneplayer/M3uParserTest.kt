package com.onelabs.oneplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class M3uParserTest {

    @Test
    fun parseSimpleM3uPlaylist() {
        val content = """
            #EXTM3U
            #EXTINF:-1 tvg-id="cnn" tvg-name="CNN HD" tvg-logo="https://example.com/cnn.png" group-title="News",CNN International
            http://example.com/cnn/live.m3u8
            #EXTINF:0 group-title="Sports",Eurosport 1
            http://example.com/eurosport/stream.ts
        """.trimIndent()

        val result = M3uParser.parse(content, "http://example.com/playlist.m3u")
        assertEquals(M3uParser.Kind.CHANNEL_LIST, result.kind)
        assertEquals(2, result.entries.size)

        val first = result.entries[0]
        assertEquals("CNN International", first.title)
        assertEquals("http://example.com/cnn/live.m3u8", first.url)
        assertEquals("News", first.group)
        assertEquals("https://example.com/cnn.png", first.logo)

        val second = result.entries[1]
        assertEquals("Eurosport 1", second.title)
        assertEquals("Sports", second.group)
    }

    @Test
    fun parseHlsMasterPlaylist() {
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=1280000,RESOLUTION=1280x720,NAME="720p HD"
            720p.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2560000,RESOLUTION=1920x1080,NAME="1080p FHD"
            1080p.m3u8
        """.trimIndent()

        val result = M3uParser.parse(master, "http://cdn.example.com/live/stream.m3u8")
        assertEquals(M3uParser.Kind.HLS_MASTER, result.kind)
        assertEquals(2, result.entries.size)
        assertEquals("720p HD", result.entries[0].title)
        assertEquals("http://cdn.example.com/live/720p.m3u8", result.entries[0].url)
        assertEquals("1080p FHD", result.entries[1].title)
        assertEquals("http://cdn.example.com/live/1080p.m3u8", result.entries[1].url)
    }

    @Test
    fun parseRelativeUrls() {
        val resolved = M3uParser.resolveUrl("http://cdn.example.com/hls/master.m3u8", "chunklist.m3u8")
        assertEquals("http://cdn.example.com/hls/chunklist.m3u8", resolved)

        val absolute = M3uParser.resolveUrl("http://cdn.example.com/hls/master.m3u8", "https://other.com/live.m3u8")
        assertEquals("https://other.com/live.m3u8", absolute)
    }

    @Test
    fun parseXtreamGetPhpUrl() {
        val url = "http://iptv.provider.net:8080/get.php?username=testuser&password=testpass&type=m3u_plus"
        val account = XtreamClient.parseGetPhpUrl(url)
        assertNotNull(account)
        assertEquals("http://iptv.provider.net:8080", account?.server)
        assertEquals("testuser", account?.username)
        assertEquals("testpass", account?.password)
    }

    @Test
    fun normalizeServerUrl() {
        assertEquals("http://myiptv.com:8080", XtreamClient.normalizeServer("myiptv.com:8080"))
        assertEquals("http://myiptv.com:8080", XtreamClient.normalizeServer("http://myiptv.com:8080/player_api.php"))
        assertEquals("https://myiptv.com", XtreamClient.normalizeServer("https://myiptv.com/get.php"))
    }
}
