package org.watermedia.api.network.patchs.youtube;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.watermedia.WaterMedia;
import org.watermedia.core.tools.DataTool;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class YtDlpClient {
    private static final long TIMEOUT_SECONDS = 120;

    private final YtDlpBinary binary;

    public YtDlpClient() {
        this.binary = new YtDlpBinary();
    }

    public JsonObject info(final String url, final List<String> extraArgs) throws BotGuardException {
        final Path exe = this.binary.executable();

        final List<String> command = new ArrayList<>();
        command.add(exe.toString());
        command.add("-J");
        command.add("--no-warnings");
        command.add("--ignore-config");
        command.add("--cache-dir");
        command.add(WaterMedia.getLoader().tempDir().resolve("yt-dlp").resolve("cache").toString());
        command.add("--socket-timeout");
        command.add("30");
        command.add("--retries");
        command.add("3");
        if (extraArgs != null) {
            command.addAll(extraArgs);
        }
        command.add(url);

        final Process process;
        try {
            process = new ProcessBuilder(command).start();
        } catch (final Exception e) {
            throw new BotGuardException("Could not start yt-dlp", e);
        }

        final StringBuilder stderr = new StringBuilder();
        final Thread drain = new Thread(new Runnable() {
            @Override
            public void run() {
                stderr.append(readAll(process.getErrorStream()));
            }
        }, "ytdlp-stderr");
        drain.setDaemon(true);
        drain.start();

        final String stdout = readAll(process.getInputStream());
        try {
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new BotGuardException("yt-dlp timed out after " + TIMEOUT_SECONDS + "s");
            }
            drain.join(1000);
        } catch (final InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new BotGuardException("Interrupted while waiting for yt-dlp", e);
        }

        final int exit = process.exitValue();
        if (exit != 0) {
            final String err = stderr.toString().trim();
            throw new BotGuardException("yt-dlp exited " + exit + ": "
                    + (err.isEmpty() ? "(no stderr)" : err));
        }
        try {
            return new JsonParser().parse(stdout).getAsJsonObject();
        } catch (final Exception e) {
            throw new BotGuardException("yt-dlp produced unparseable JSON", e);
        }
    }

    private static String readAll(final InputStream in) {
        try {
            byte[] bytes = DataTool.readAllBytes(in);
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (final Exception e) {
            return "";
        }
    }
}
