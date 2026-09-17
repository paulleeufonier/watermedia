package org.watermedia.api.network.patchs.youtube;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.watermedia.WaterMedia;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.watermedia.WaterMedia.LOGGER;

public final class YtDlpBinary {
    private static final String MANIFEST = "/ytdlp/manifest.json";
    private static final String OVERRIDE_PROPERTY = "watermedia.ytdlp.binary";

    private final String version;
    private final String url;
    private final String sha256;
    private final String binaryName;
    private final String unsupportedReason;

    private volatile Path ready;

    public YtDlpBinary() {
        String ver = null;
        String u = null;
        String hash = null;
        String name = null;
        String reason = null;

        try {
            JsonObject manifest;
            try (InputStream in = YtDlpBinary.class.getResourceAsStream(MANIFEST)) {
                if (in == null) throw new IllegalStateException("missing " + MANIFEST);
                manifest = new JsonParser().parse(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            }
            ver = manifest.get("version").getAsString();
            final String base = manifest.get("baseUrl").getAsString();

            final String os = NativeBinaries.os();
            final String arch = NativeBinaries.arch();
            final String key = os == null ? null : (os.equals("macos") ? "macos" : os + "-" + arch);
            final JsonObject assets = manifest.getAsJsonObject("assets");
            if (key == null || arch == null || !assets.has(key)) {
                reason = "No yt-dlp build for " + os + "/" + arch;
            } else {
                final JsonObject asset = assets.getAsJsonObject(key);
                name = asset.get("name").getAsString();
                hash = asset.get("sha256").getAsString();
                u = base + ver + "/" + name;
            }
        } catch (final Exception e) {
            reason = "Could not read yt-dlp manifest: " + e.getMessage();
        }

        this.version = ver;
        this.url = u;
        this.sha256 = hash;
        this.binaryName = name;
        this.unsupportedReason = reason;
    }

    public String version() {
        return this.version;
    }

    public Path executable() throws BotGuardException {
        Path p = this.ready;
        if (p != null) return p;
        synchronized (this) {
            if (this.ready != null) return this.ready;

            final String override = System.getProperty(OVERRIDE_PROPERTY);
            if (override != null && !override.trim().isEmpty()) {
                final Path o = Paths.get(override);
                if (!Files.isRegularFile(o)) {
                    throw new BotGuardException("watermedia.ytdlp.binary points to a missing file: " + o);
                }
                NativeBinaries.makeExecutable(o);
                LOGGER.info("Using overridden yt-dlp binary: {}", o);
                return this.ready = o;
            }

            if (this.unsupportedReason != null) {
                throw new BotGuardException(this.unsupportedReason);
            }

            final Path binary = WaterMedia.getLoader().tempDir().resolve("yt-dlp").resolve(this.version).resolve(this.binaryName);
            try {
                if (!Files.isRegularFile(binary)) {
                    Files.createDirectories(binary.getParent());
                    LOGGER.info("Downloading yt-dlp {} from {}", this.version, this.url);
                    NativeBinaries.download(this.url, binary);
                    NativeBinaries.verify(binary, this.sha256);
                }
                NativeBinaries.makeExecutable(binary);
            } catch (final BotGuardException e) {
                Path sys = findOnPath(this.binaryName);
                if (sys != null) {
                    LOGGER.warn("Failed to download yt-dlp ({}), falling back to system binary: {}", e.getMessage(), sys);
                    return this.ready = sys;
                }
                throw e;
            } catch (final Exception e) {
                Path sys = findOnPath(this.binaryName);
                if (sys != null) {
                    LOGGER.warn("Failed to provision yt-dlp ({}), falling back to system binary: {}", e.getMessage(), sys);
                    return this.ready = sys;
                }
                throw new BotGuardException("Failed to provision yt-dlp", e);
            }
            LOGGER.info("yt-dlp {} ready at {}", this.version, binary);
            return this.ready = binary;
        }
    }

    private static Path findOnPath(String name) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) return null;
        for (String dir : pathEnv.split(File.pathSeparator)) {
            try {
                Path candidate = Paths.get(dir).resolve(name);
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                    return candidate;
                }
            } catch (Exception ignored) {}
        }
        return null;
    }
}
