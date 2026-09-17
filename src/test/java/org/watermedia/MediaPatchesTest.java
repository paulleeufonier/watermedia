package org.watermedia;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.watermedia.api.network.NetworkAPI;
import org.watermedia.api.network.patchs.KickPatch;
import org.watermedia.api.network.patchs.TwitchPatch;
import org.watermedia.api.network.patchs.YoutubePatch;
import org.watermedia.api.network.patchs.youtube.BotGuardBinary;
import org.watermedia.api.network.patchs.youtube.NativeBinaries;
import org.watermedia.api.network.patchs.youtube.YtDlpBinary;
import org.watermedia.loaders.ILoader;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.*;

class MediaPatchesTest {

    @BeforeAll
    static void init() throws Exception {
        new NetworkAPI().start(ILoader.DEFAULT);
    }

    @Test
    void testYoutubePatchValidity() {
        YoutubePatch patch = new YoutubePatch();
        assertEquals("Youtube", patch.platform());

        // Valid YouTube URLs
        assertTrue(patch.isValid(URI.create("https://www.youtube.com/watch?v=dQw4w9WgXcQ")));
        assertTrue(patch.isValid(URI.create("https://youtu.be/dQw4w9WgXcQ")));
        assertTrue(patch.isValid(URI.create("https://youtube.com/shorts/36lSzUMBJnc")));
        assertTrue(patch.isValid(URI.create("https://www.youtube.com/embed/30KNoyEYCag")));
        assertTrue(patch.isValid(URI.create("https://www.youtube.com/watch?feature=shared&v=9A2d2-V-4as")));

        // Invalid URLs
        assertFalse(patch.isValid(URI.create("https://www.google.com")));
        assertFalse(patch.isValid(URI.create("https://www.twitch.tv/streamer")));
        assertFalse(patch.isValid(URI.create("https://kick.com/streamer")));
    }

    @Test
    void testKickPatchValidity() {
        KickPatch patch = new KickPatch();
        assertEquals("Kick", patch.platform());

        assertTrue(patch.isValid(URI.create("https://kick.com/opiate")));
        assertTrue(patch.isValid(URI.create("https://kick.com/video/12345")));
        assertTrue(patch.isValid(URI.create("https://subdomain.kick.com/channel")));

        assertFalse(patch.isValid(URI.create("https://www.youtube.com/watch?v=123")));
        assertFalse(patch.isValid(URI.create("https://twitch.tv/streamer")));
    }

    @Test
    void testTwitchPatchValidity() {
        TwitchPatch patch = new TwitchPatch();
        assertEquals("Twitch", patch.platform());

        assertTrue(patch.isValid(URI.create("https://www.twitch.tv/ironmouse")));
        assertTrue(patch.isValid(URI.create("https://twitch.tv/videos/123456789")));
        assertTrue(patch.isValid(URI.create("https://twitch.tv/streamer")));

        assertFalse(patch.isValid(URI.create("https://kick.com/streamer")));
        assertFalse(patch.isValid(URI.create("https://youtube.com/watch?v=abc")));
    }

    @Test
    void testNativeBinaries() {
        assertNotNull(NativeBinaries.os(), "OS should be recognized on test platform");
        assertNotNull(NativeBinaries.arch(), "Arch should be recognized on test platform");
    }

    @Test
    void testYtDlpBinaryManifest() {
        YtDlpBinary binary = new YtDlpBinary();
        assertNotNull(binary.version(), "Manifest version should be readable");
        assertEquals("2026.06.09", binary.version());
    }

    @Test
    void testBotGuardBinary() {
        BotGuardBinary binary = new BotGuardBinary();
        assertEquals("0.7.0", BotGuardBinary.VERSION);
    }
}
