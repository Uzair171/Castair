package com.castair

import com.castair.protocol.CastItem
import com.castair.protocol.Command
import com.castair.protocol.Event
import com.castair.protocol.PairingInfo
import com.castair.protocol.PlayStatus
import com.castair.protocol.parseCommand
import com.castair.protocol.parseEvent
import com.castair.protocol.toJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CastairUnitTest {

    @Test
    fun testCommandPlaySerialization() {
        val item = CastItem(
            url = "https://example.com/video.mp4",
            title = "Test Video",
            headers = mapOf("Referer" to "https://example.com", "User-Agent" to "CastairBrowser")
        )
        val cmd: Command = Command.Play(item)
        val json = cmd.toJson()

        val parsed = parseCommand(json)
        assertTrue(parsed is Command.Play)
        val playCmd = parsed as Command.Play
        assertEquals("https://example.com/video.mp4", playCmd.item.url)
        assertEquals("Test Video", playCmd.item.title)
        assertEquals("https://example.com", playCmd.item.headers["Referer"])
    }

    @Test
    fun testEventStateSerialization() {
        val event: Event = Event.State(
            status = PlayStatus.PLAYING,
            title = "Sintel Trailer",
            positionMs = 15000,
            durationMs = 60000,
            volumePercent = 80
        )
        val json = event.toJson()

        val parsed = parseEvent(json)
        assertTrue(parsed is Event.State)
        val stateEvent = parsed as Event.State
        assertEquals(PlayStatus.PLAYING, stateEvent.status)
        assertEquals("Sintel Trailer", stateEvent.title)
        assertEquals(15000L, stateEvent.positionMs)
        assertEquals(60000L, stateEvent.durationMs)
        assertEquals(80, stateEvent.volumePercent)
    }

    @Test
    fun testPairingInfoUriParsing() {
        val info = PairingInfo("192.168.1.50", 8765, "key123", "sha256abc")
        val uri = info.toUri()
        assertEquals("castair://pair?host=192.168.1.50&port=8765&key=key123&fp=sha256abc", uri)

        val parsed = PairingInfo.parse(uri)
        assertNotNull(parsed)
        assertEquals("192.168.1.50", parsed?.host)
        assertEquals(8765, parsed?.port)
        assertEquals("key123", parsed?.pairKey)
        assertEquals("sha256abc", parsed?.certSha256)
    }

    @Test
    fun testVideoSnifferIsVideoUrl() {
        assertTrue(VideoSniffer.isVideoUrl("https://domain.com/live/stream.m3u8"))
        assertTrue(VideoSniffer.isVideoUrl("https://domain.com/media/manifest.mpd"))
        assertTrue(VideoSniffer.isVideoUrl("https://domain.com/media/sample.mp4"))
        assertTrue(!VideoSniffer.isVideoUrl("https://domain.com/index.html"))
        assertTrue(!VideoSniffer.isVideoUrl("blob:https://domain.com/123-456"))
    }

    @Test
    fun testVideoSnifferIsVideoMime() {
        assertTrue(VideoSniffer.isVideoMime("video/mp4"))
        assertTrue(VideoSniffer.isVideoMime("application/x-mpegurl"))
        assertTrue(VideoSniffer.isVideoMime("application/dash+xml"))
        assertTrue(!VideoSniffer.isVideoMime("text/html"))
    }
}
