package com.damarquez.putz.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoPlaybackHintTest {
    @Test
    fun `xvid avi releases are flagged`() {
        assertEquals(
            "Likely needs conversion for Plex: XviD/DivX video, AVI container",
            VideoPlaybackHint.hint("Prometheus.2012.DVDRip.XviD-MAXSPEED.avi"),
        )
        assertEquals(
            "Likely needs conversion for Plex: XviD/DivX video",
            VideoPlaybackHint.hint("Some.Show.S01E02.HDTV.XviD-LOL"),
        )
    }

    @Test
    fun `modern releases pass`() {
        assertNull(VideoPlaybackHint.hint("Pacific.Rim.2013.1080p.BluRay.x264.YIFY.mp4"))
        assertNull(VideoPlaybackHint.hint("Movie.2021.2160p.WEB-DL.DDP5.1.HEVC-GRP.mkv"))
        assertNull(VideoPlaybackHint.hint("Movie.2021.1080p.BluRay.x265.10bit.AC3.mkv"))
        // DTS/TrueHD are converted live by Plex cheaply — not flagged.
        assertNull(VideoPlaybackHint.hint("Movie.2019.1080p.BluRay.DTS-HD.MA.5.1.x264-GRP"))
    }

    @Test
    fun `other problem formats`() {
        assertEquals("Likely needs conversion for Plex: WMV video",
            VideoPlaybackHint.hint("Lecture 01.wmv"))
        assertEquals("Likely needs conversion for Plex: AV1 video",
            VideoPlaybackHint.hint("Movie.2023.1080p.WEB.AV1.Opus-GRP.mkv"))
        assertEquals("Likely needs conversion for Plex: 10-bit H.264",
            VideoPlaybackHint.hint("[Group] Anime - 01 [1080p Hi10P x264].mkv"))
        assertEquals("Likely needs conversion for Plex: DVD image",
            VideoPlaybackHint.hint("Old.Movie.1985.DVD9.iso"))
    }

    @Test
    fun `non video names are ignored`() {
        assertNull(VideoPlaybackHint.hint("Some Book - Author.epub"))
        assertNull(VideoPlaybackHint.hint("Album (2001) [FLAC]"))
        assertNull(VideoPlaybackHint.hint("Ubuntu 24.04 iso installer.zip"))
        assertNull(VideoPlaybackHint.hint("Avid Reader Collection"))
    }

    @Test
    fun `stub suffix is ignored`() {
        assertEquals(
            "Likely needs conversion for Plex: XviD/DivX video, AVI container",
            VideoPlaybackHint.hint("Movie.2004.XviD.avi.sk_synced"),
        )
    }
}
