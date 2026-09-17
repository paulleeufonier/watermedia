package org.watermedia.api.network.patchs.youtube;

import org.watermedia.core.tools.NetTool;

import java.io.BufferedInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Locale;

public final class NativeBinaries {
    private NativeBinaries() {}

    public static String os() {
        final String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) return "windows";
        if (os.contains("mac") || os.contains("darwin")) return "macos";
        if (os.contains("nux") || os.contains("nix") || os.contains("aix")) return "linux";
        return null;
    }

    public static String arch() {
        final String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (arch.equals("amd64") || arch.equals("x86_64") || arch.equals("x64")) return "x86_64";
        if (arch.equals("aarch64") || arch.equals("arm64")) return "aarch64";
        return null;
    }

    public static void download(final String urlStr, final Path dest) throws Exception {
        String targetUrl = urlStr;
        HttpURLConnection conn = null;
        for (int redirects = 0; redirects < 10; redirects++) {
            conn = NetTool.connectToHTTP(URI.create(targetUrl), "GET");
            conn.setInstanceFollowRedirects(false);
            conn.setRequestProperty("User-Agent", NetTool.USER_AGENT);
            int code = conn.getResponseCode();
            if (code == HttpURLConnection.HTTP_MOVED_PERM || code == HttpURLConnection.HTTP_MOVED_TEMP || code == 307 || code == 308) {
                String location = conn.getHeaderField("Location");
                if (location != null && !location.isEmpty()) {
                    conn.disconnect();
                    targetUrl = location;
                    continue;
                }
            }
            if (code != HttpURLConnection.HTTP_OK) {
                conn.disconnect();
                throw new BotGuardException("Download failed (HTTP " + code + "): " + urlStr);
            }
            break;
        }

        if (conn == null) {
            throw new BotGuardException("Could not connect to " + urlStr);
        }

        Files.createDirectories(dest.getParent());
        try (InputStream in = new BufferedInputStream(conn.getInputStream());
             OutputStream out = new FileOutputStream(dest.toFile())) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        } finally {
            conn.disconnect();
        }
    }

    public static void verify(final Path file, final String expected) throws Exception {
        final String actual = sha256(file);
        if (!expected.equalsIgnoreCase(actual)) {
            Files.deleteIfExists(file);
            throw new BotGuardException("SHA-256 mismatch for " + file.getFileName()
                    + " (expected " + expected + ", got " + actual + ")");
        }
    }

    public static String sha256(final Path file) throws Exception {
        final MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (final InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
            final byte[] buffer = new byte[65536];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        final byte[] hash = digest.digest();
        final StringBuilder sb = new StringBuilder(hash.length * 2);
        for (final byte b : hash) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    public static void makeExecutable(final Path file) {
        file.toFile().setExecutable(true);
    }
}
