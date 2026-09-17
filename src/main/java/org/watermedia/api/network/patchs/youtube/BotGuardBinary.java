package org.watermedia.api.network.patchs.youtube;

import org.tukaani.xz.XZInputStream;
import org.watermedia.WaterMedia;
import org.watermedia.core.tools.IOTool;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.watermedia.WaterMedia.LOGGER;

public final class BotGuardBinary {
    public static final String VERSION = "0.7.0";
    private static final String BASE_URL =
            "https://github.com/ThetaDev/rustypipe-botguard/releases/download/v" + VERSION + "/";
    private static final String SNAPSHOT_FILE = "snapshot.bin";
    private static final String OVERRIDE_PROPERTY = "watermedia.botguard.binary";

    private final String asset;
    private final String sha256;
    private final boolean zip;
    private final String binaryName;
    private final String unsupportedReason;

    private volatile Path ready;

    public BotGuardBinary() {
        final String os = NativeBinaries.os();
        final String arch = NativeBinaries.arch();
        final String key = (os == null || arch == null) ? null : (os + "-" + arch);

        String a = null;
        String hash = null;
        boolean isZip = false;
        String name = "rustypipe-botguard";
        String reason = null;

        if ("windows-x86_64".equals(key)) {
            a = "rustypipe-botguard-v" + VERSION + "-x86_64-pc-windows-msvc.zip";
            hash = "9154b036cb538058a5c689d09cbb5cb5043bf846ee454eb89aa21ff27a44f514";
            isZip = true;
            name = "rustypipe-botguard.exe";
        } else if ("macos-aarch64".equals(key)) {
            a = "rustypipe-botguard-v" + VERSION + "-aarch64-apple-darwin.tar.xz";
            hash = "472905be9612edec129690d62d4db0321d1f30a548e59f68f89b7374fad9606e";
        } else if ("macos-x86_64".equals(key)) {
            a = "rustypipe-botguard-v" + VERSION + "-x86_64-apple-darwin.tar.xz";
            hash = "8cfb319de5498dee5fb9ce5c88587e7bacab59e0550a38c9ad52dae840249cef";
        } else if ("linux-aarch64".equals(key)) {
            a = "rustypipe-botguard-v" + VERSION + "-aarch64-unknown-linux-gnu.tar.xz";
            hash = "4d038857374a69aea9be8ded981d93a776dc88d4e254f5c6d292746099abf69a";
        } else if ("linux-x86_64".equals(key)) {
            a = "rustypipe-botguard-v" + VERSION + "-x86_64-unknown-linux-gnu.tar.xz";
            hash = "4f2ec561e8f9fadece7deadc6ce0624fbdedd852222c3eb194c22153b1323129";
        } else {
            reason = "No rustypipe-botguard build for "
                    + System.getProperty("os.name") + "/" + System.getProperty("os.arch");
        }

        this.asset = a;
        this.sha256 = hash;
        this.zip = isZip;
        this.binaryName = name;
        this.unsupportedReason = reason;
    }

    private Path versionDir() {
        return WaterMedia.getLoader().tempDir().resolve("botguard").resolve(VERSION);
    }

    public Path snapshot() {
        return this.versionDir().resolve(SNAPSHOT_FILE);
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
                    throw new BotGuardException("watermedia.botguard.binary points to a missing file: " + o);
                }
                NativeBinaries.makeExecutable(o);
                LOGGER.info("Using overridden rustypipe-botguard binary: {}", o);
                return this.ready = o;
            }

            if (this.unsupportedReason != null) {
                throw new BotGuardException(this.unsupportedReason);
            }

            final Path binary = this.versionDir().resolve(this.binaryName);
            try {
                if (!Files.isRegularFile(binary)) {
                    this.install(binary);
                }
                NativeBinaries.makeExecutable(binary);
            } catch (final BotGuardException e) {
                throw e;
            } catch (final Exception e) {
                throw new BotGuardException("Failed to provision rustypipe-botguard", e);
            }
            LOGGER.info("rustypipe-botguard {} ready at {}", VERSION, binary);
            return this.ready = binary;
        }
    }

    private void install(final Path binary) throws Exception {
        final Path versionDir = binary.getParent();
        Files.createDirectories(versionDir);
        final Path archive = versionDir.resolve(this.asset);

        LOGGER.info("Downloading rustypipe-botguard from {}", BASE_URL + this.asset);
        NativeBinaries.download(BASE_URL + this.asset, archive);
        NativeBinaries.verify(archive, this.sha256);

        if (this.zip) {
            IOTool.unzip(archive);
        } else {
            extractTarXz(archive, binary);
        }
        Files.deleteIfExists(archive);

        if (!Files.isRegularFile(binary)) {
            throw new BotGuardException("Extraction did not produce the expected binary: " + binary);
        }
    }

    private static void extractTarXz(final Path archive, final Path destination) throws Exception {
        try (InputStream fin = Files.newInputStream(archive);
             XZInputStream xz = new XZInputStream(new BufferedInputStream(fin))) {
            final byte[] header = new byte[512];
            while (readFully(xz, header) == 512) {
                if (isAllZero(header)) break;
                final long size = parseOctal(header, 124, 12);
                final char type = (char) (header[156] & 0xFF);
                if ((type == '0' || type == '\0') && size > 0) {
                    try (OutputStream out = Files.newOutputStream(destination)) {
                        copyExactly(xz, out, size);
                    }
                    return;
                }
                skipExactly(xz, size + padding(size));
            }
            throw new BotGuardException("No regular file inside " + archive.getFileName());
        }
    }

    private static long parseOctal(final byte[] header, final int offset, final int length) {
        long value = 0;
        for (int i = offset; i < offset + length; i++) {
            final int c = header[i] & 0xFF;
            if (c == 0 || c == ' ') continue;
            if (c < '0' || c > '7') break;
            value = (value << 3) + (c - '0');
        }
        return value;
    }

    private static long padding(final long size) {
        final long remainder = size % 512;
        return remainder == 0 ? 0 : 512 - remainder;
    }

    private static boolean isAllZero(final byte[] block) {
        for (final byte b : block) {
            if (b != 0) return false;
        }
        return true;
    }

    private static int readFully(final InputStream in, final byte[] buffer) throws IOException {
        int total = 0;
        int read;
        while (total < buffer.length && (read = in.read(buffer, total, buffer.length - total)) != -1) {
            total += read;
        }
        return total;
    }

    private static void copyExactly(final InputStream in, final OutputStream out, final long count) throws IOException {
        final byte[] buffer = new byte[65536];
        long remaining = count;
        while (remaining > 0) {
            final int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read == -1) throw new IOException("Truncated tar entry");
            out.write(buffer, 0, read);
            remaining -= read;
        }
    }

    private static void skipExactly(final InputStream in, final long count) throws IOException {
        long remaining = count;
        while (remaining > 0) {
            final long skipped = in.skip(remaining);
            if (skipped <= 0) {
                if (in.read() == -1) return;
                remaining--;
            } else {
                remaining -= skipped;
            }
        }
    }
}
