package dev.trmx.gui.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Unit tests for Instagram Downloader pure logic:
 * - URL and handle parsing (profiles, posts, reels, stories with ID, story trays, highlights, IGTV)
 * - Upfront quality mapping and label resolution (Video resolutions, Audio bitrates)
 * - Video / MP3 mode selection and yt-dlp argv synthesis
 * - On-device download history store (add, update, list sorted, remove, clear, retry state machine)
 */
class InstagramDownloaderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // ------------------------------------------------------------------------
    // 1. Link and Handle Parsing Tests
    // ------------------------------------------------------------------------

    @Test
    fun `parses standard profile URL`() {
        val link = InstagramUrlParser.parse("https://www.instagram.com/therock/")
        assertEquals(InstagramUrlType.PROFILE, link.type)
        assertEquals("therock", link.username)
        assertEquals("https://www.instagram.com/therock/", link.canonicalUrl)
        assertNull(link.error)
    }

    @Test
    fun `parses profile URL with tracking query parameters`() {
        val link = InstagramUrlParser.parse("https://instagram.com/natgeo?igsh=OGQ5ZDc2ODk2ZA==")
        assertEquals(InstagramUrlType.PROFILE, link.type)
        assertEquals("natgeo", link.username)
        assertEquals("https://www.instagram.com/natgeo/", link.canonicalUrl)
    }

    @Test
    fun `parses profile from at-prefixed handle`() {
        val link = InstagramUrlParser.parse("@nasa")
        assertEquals(InstagramUrlType.PROFILE, link.type)
        assertEquals("nasa", link.username)
        assertEquals("https://www.instagram.com/nasa/", link.canonicalUrl)
    }

    @Test
    fun `parses profile from raw username`() {
        val link = InstagramUrlParser.parse("cristiano")
        assertEquals(InstagramUrlType.PROFILE, link.type)
        assertEquals("cristiano", link.username)
        assertEquals("https://www.instagram.com/cristiano/", link.canonicalUrl)
    }

    @Test
    fun `parses post URL`() {
        val link = InstagramUrlParser.parse("https://www.instagram.com/p/DF123abcXYZ/")
        assertEquals(InstagramUrlType.POST, link.type)
        assertEquals("DF123abcXYZ", link.shortcode)
        assertEquals("https://www.instagram.com/p/DF123abcXYZ/", link.canonicalUrl)
    }

    @Test
    fun `parses reel URL`() {
        val link = InstagramUrlParser.parse("https://www.instagram.com/reel/C8XYZ123/")
        assertEquals(InstagramUrlType.REEL, link.type)
        assertEquals("C8XYZ123", link.shortcode)
        assertEquals("https://www.instagram.com/reel/C8XYZ123/", link.canonicalUrl)
    }

    @Test
    fun `parses reels plural URL`() {
        val link = InstagramUrlParser.parse("https://instagram.com/reels/C8XYZ123?utm_source=ig_web_copy_link")
        assertEquals(InstagramUrlType.REEL, link.type)
        assertEquals("C8XYZ123", link.shortcode)
        assertEquals("https://www.instagram.com/reel/C8XYZ123/", link.canonicalUrl)
    }

    @Test
    fun `parses story URL with specific media ID`() {
        val link = InstagramUrlParser.parse("https://www.instagram.com/stories/therock/1234567890123456789/")
        assertEquals(InstagramUrlType.STORY, link.type)
        assertEquals("therock", link.username)
        assertEquals("1234567890123456789", link.storyId)
        assertEquals("https://www.instagram.com/stories/therock/1234567890123456789/", link.canonicalUrl)
    }

    @Test
    fun `parses story tray URL`() {
        val link = InstagramUrlParser.parse("https://instagram.com/stories/therock/")
        assertEquals(InstagramUrlType.STORY, link.type)
        assertEquals("therock", link.username)
        assertNull(link.storyId)
        assertEquals("https://www.instagram.com/stories/therock/", link.canonicalUrl)
    }

    @Test
    fun `parses highlight URL`() {
        val link = InstagramUrlParser.parse("https://www.instagram.com/stories/highlights/17987654321/")
        assertEquals(InstagramUrlType.HIGHLIGHT, link.type)
        assertEquals("17987654321", link.highlightId)
        assertEquals("https://www.instagram.com/stories/highlights/17987654321/", link.canonicalUrl)
    }

    @Test
    fun `parses IGTV URL`() {
        val link = InstagramUrlParser.parse("https://www.instagram.com/tv/CZ98765/")
        assertEquals(InstagramUrlType.POST, link.type)
        assertEquals("CZ98765", link.shortcode)
        assertEquals("https://www.instagram.com/tv/CZ98765/", link.canonicalUrl)
    }

    @Test
    fun `rejects invalid and empty inputs gracefully`() {
        assertEquals(InstagramUrlType.UNKNOWN, InstagramUrlParser.parse("").type)
        assertEquals(InstagramUrlType.UNKNOWN, InstagramUrlParser.parse("   ").type)
        assertEquals(InstagramUrlType.UNKNOWN, InstagramUrlParser.parse("https://google.com").type)
        assertEquals(InstagramUrlType.UNKNOWN, InstagramUrlParser.parse("https://instagram.com/explore/").type)
    }

    // ------------------------------------------------------------------------
    // 2. Quality & Mode Mapping Tests
    // ------------------------------------------------------------------------

    @Test
    fun `video quality resolutions map accurately to yt-dlp format strings`() {
        assertEquals(1080, VideoQuality.P1080.height)
        assertTrue(VideoQuality.P1080.ytdlpFormat.contains("height<=1080"))

        assertEquals(720, VideoQuality.P720.height)
        assertTrue(VideoQuality.P720.ytdlpFormat.contains("height<=720"))

        assertEquals(480, VideoQuality.P480.height)
        assertTrue(VideoQuality.P480.ytdlpFormat.contains("height<=480"))

        assertNull(VideoQuality.ORIGINAL.height)
        assertEquals("bestvideo+bestaudio/best", VideoQuality.ORIGINAL.ytdlpFormat)

        assertEquals(VideoQuality.P1080, VideoQuality.fromLabel("1080p"))
        assertEquals(VideoQuality.P720, VideoQuality.fromLabel("720p"))
        assertEquals(VideoQuality.P480, VideoQuality.fromLabel("480p"))
        assertEquals(VideoQuality.ORIGINAL, VideoQuality.fromLabel("Original"))
        assertEquals(VideoQuality.ORIGINAL, VideoQuality.fromLabel("Unknown"))
    }

    @Test
    fun `audio quality bitrates map accurately to yt-dlp quality strings`() {
        assertEquals(320, AudioQuality.KBPS320.bitrateKbps)
        assertEquals("320K", AudioQuality.KBPS320.ytdlpQuality)

        assertEquals(192, AudioQuality.KBPS192.bitrateKbps)
        assertEquals("192K", AudioQuality.KBPS192.ytdlpQuality)

        assertEquals(128, AudioQuality.KBPS128.bitrateKbps)
        assertEquals("128K", AudioQuality.KBPS128.ytdlpQuality)

        assertEquals(AudioQuality.KBPS320, AudioQuality.fromLabel("320 kbps"))
        assertEquals(AudioQuality.KBPS192, AudioQuality.fromLabel("192 kbps"))
        assertEquals(AudioQuality.KBPS128, AudioQuality.fromLabel("128 kbps"))
    }

    // ------------------------------------------------------------------------
    // 3. Engine Argv Synthesis Tests
    // ------------------------------------------------------------------------

    @Test
    fun `synthesizes video mode argv with quality constraint`() {
        val argv = DownloadEngine.buildYtdlpArgv(
            url = "https://www.instagram.com/reel/C8XYZ123/",
            mode = MediaMode.VIDEO,
            videoQuality = VideoQuality.P1080,
            audioQuality = AudioQuality.KBPS320,
            option = DownloadOption.MEDIA
        )
        assertTrue(argv.contains("yt-dlp"))
        assertTrue(argv.contains("--newline"))
        assertTrue(argv.contains("-f"))
        assertTrue(argv.contains(VideoQuality.P1080.ytdlpFormat))
        assertTrue(argv.contains("-P"))
        assertTrue(argv.contains("~/downloads"))
        assertEquals("https://www.instagram.com/reel/C8XYZ123/", argv.last())
    }

    @Test
    fun `synthesizes mp3 mode argv extracting audio only with bitrate`() {
        val argv = DownloadEngine.buildYtdlpArgv(
            url = "https://www.instagram.com/reel/C8XYZ123/",
            mode = MediaMode.MP3,
            videoQuality = VideoQuality.P1080,
            audioQuality = AudioQuality.KBPS320,
            option = DownloadOption.MEDIA
        )
        assertTrue(argv.contains("-x"))
        assertTrue(argv.contains("--audio-format"))
        assertTrue(argv.contains("mp3"))
        assertTrue(argv.contains("--audio-quality"))
        assertTrue(argv.contains("320K"))
        assertFalse(argv.contains("height<=1080")) // no video format wasted
    }

    @Test
    fun `synthesizes cover image extraction argv`() {
        val argv = DownloadEngine.buildYtdlpArgv(
            url = "https://www.instagram.com/reel/C8XYZ123/",
            mode = MediaMode.VIDEO,
            videoQuality = VideoQuality.P1080,
            audioQuality = AudioQuality.KBPS320,
            option = DownloadOption.COVER_IMAGE
        )
        assertTrue(argv.contains("--skip-download"))
        assertTrue(argv.contains("--write-thumbnail"))
        assertTrue(argv.contains("--convert-thumbnails"))
        assertTrue(argv.contains("jpg"))
    }

    @Test
    fun `synthesizes carousel all items download argv in full hd`() {
        val argv = DownloadEngine.buildYtdlpArgv(
            url = "https://www.instagram.com/p/DF123abc/",
            mode = MediaMode.VIDEO,
            videoQuality = VideoQuality.P1080,
            audioQuality = AudioQuality.KBPS320,
            option = DownloadOption.CAROUSEL_ALL
        )
        assertTrue(argv.contains("--yes-playlist"))
        assertTrue(argv.contains("bestvideo+bestaudio/best"))
    }

    // ------------------------------------------------------------------------
    // 4. Download History Store Tests
    // ------------------------------------------------------------------------

    @Test
    fun `history store starts empty and persists added records`() {
        val storeDir = tempFolder.newFolder("history_test")
        val store = DownloadHistoryStore(storeDir)

        assertTrue(store.list().isEmpty())

        val record = DownloadRecord(
            id = "rec-1",
            originalUrl = "https://www.instagram.com/p/DF123/",
            title = "Test Post",
            itemType = "POST",
            mediaMode = "VIDEO",
            quality = "1080p",
            targetPath = "~/downloads/DF123",
            status = "COMPLETED",
            progressPct = 100f,
            createdAt = 1000L
        )

        store.add(record)
        assertEquals(1, store.list().size)
        assertEquals(record, store.byId("rec-1"))

        // Create new store instance pointing to same dir to test disk persistence
        val reloaded = DownloadHistoryStore(storeDir)
        assertEquals(1, reloaded.list().size)
        assertEquals("Test Post", reloaded.list().first().title)
    }

    @Test
    fun `history store orders items by newest first`() {
        val storeDir = tempFolder.newFolder("history_order")
        val store = DownloadHistoryStore(storeDir)

        val r1 = DownloadRecord("id-1", "https://x.com/1", "Old", "POST", "VIDEO", "1080p", "~/downloads", "COMPLETED", createdAt = 1000L)
        val r2 = DownloadRecord("id-2", "https://x.com/2", "New", "REEL", "VIDEO", "1080p", "~/downloads", "COMPLETED", createdAt = 2000L)
        val r3 = DownloadRecord("id-3", "https://x.com/3", "Newest", "STORY", "MP3", "320 kbps", "~/downloads", "COMPLETED", createdAt = 3000L)

        store.add(r1)
        store.add(r2)
        store.add(r3)

        val list = store.list()
        assertEquals(listOf("id-3", "id-2", "id-1"), list.map { it.id })
    }

    @Test
    fun `history store updates existing records`() {
        val storeDir = tempFolder.newFolder("history_update")
        val store = DownloadHistoryStore(storeDir)

        val r = DownloadRecord("id-1", "https://x.com/1", "Title", "POST", "VIDEO", "1080p", "~/downloads", "DOWNLOADING", progressPct = 20f)
        store.add(r)

        val updated = r.copy(status = "COMPLETED", progressPct = 100f, progressDetail = "Done")
        store.update(updated)

        assertEquals("COMPLETED", store.byId("id-1")?.status)
        assertEquals(100f, store.byId("id-1")?.progressPct)
    }

    @Test
    fun `history store retry state machine resets failed record to queued`() {
        val storeDir = tempFolder.newFolder("history_retry")
        val store = DownloadHistoryStore(storeDir)

        val failed = DownloadRecord(
            id = "rec-fail",
            originalUrl = "https://www.instagram.com/p/DF123/",
            title = "Failed Download",
            itemType = "POST",
            mediaMode = "VIDEO",
            quality = "1080p",
            targetPath = "~/downloads/DF123",
            status = "FAILED",
            error = "Bridge unreachable",
            endedAt = 5000L
        )
        store.add(failed)

        val retried = store.retry("rec-fail")
        assertNotNull(retried)
        assertEquals("QUEUED", retried?.status)
        assertEquals(0f, retried?.progressPct)
        assertNull(retried?.error)
        assertNull(retried?.endedAt)
        assertEquals("Retrying…", retried?.progressDetail)

        // Verify state is persisted
        val fromStore = store.byId("rec-fail")
        assertEquals("QUEUED", fromStore?.status)
        assertNull(fromStore?.error)
    }

    @Test
    fun `history store clears all records`() {
        val storeDir = tempFolder.newFolder("history_clear")
        val store = DownloadHistoryStore(storeDir)

        store.add(DownloadRecord("1", "url", "t1", "POST", "VIDEO", "1080p", "~/downloads", "COMPLETED"))
        store.add(DownloadRecord("2", "url", "t2", "POST", "VIDEO", "1080p", "~/downloads", "COMPLETED"))
        assertEquals(2, store.list().size)

        store.clear()
        assertTrue(store.list().isEmpty())
    }
}
