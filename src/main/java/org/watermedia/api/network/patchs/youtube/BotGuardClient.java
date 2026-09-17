package org.watermedia.api.network.patchs.youtube;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.watermedia.WaterMedia.LOGGER;

public final class BotGuardClient {
    private static final long PROCESS_TIMEOUT_SECONDS = 60;
    private static final long EXPIRY_SKEW_SECONDS = 120;
    private static final long FALLBACK_LIFETIME_SECONDS = 3600;

    private final BotGuardBinary binary;
    private final Map<String, CachedToken> cache = new ConcurrentHashMap<>();
    private final Object runLock = new Object();

    public BotGuardClient() {
        this.binary = new BotGuardBinary();
    }

    public String mint(final String identifier) throws BotGuardException {
        Map<String, String> tokens = this.mint(Collections.singletonList(identifier));
        return tokens != null ? tokens.get(identifier) : null;
    }

    public Map<String, String> mint(final Collection<String> identifiers) throws BotGuardException {
        final long now = System.currentTimeMillis() / 1000L;
        final Map<String, String> result = new HashMap<>();
        final List<String> missing = new ArrayList<>();

        for (final String id : identifiers) {
            final CachedToken cached = this.cache.get(id);
            if (cached != null && cached.expiry > now + EXPIRY_SKEW_SECONDS) {
                result.put(id, cached.value);
            } else {
                missing.add(id);
            }
        }
        if (missing.isEmpty()) {
            return result;
        }

        synchronized (this.runLock) {
            for (int i = missing.size() - 1; i >= 0; i--) {
                String id = missing.get(i);
                CachedToken cached = this.cache.get(id);
                if (cached != null && cached.expiry > now + EXPIRY_SKEW_SECONDS) {
                    result.put(id, cached.value);
                    missing.remove(i);
                }
            }
            if (!missing.isEmpty()) {
                this.run(missing, result);
            }
        }
        return result;
    }

    private void run(final List<String> identifiers, final Map<String, String> result) throws BotGuardException {
        final Path exe = this.binary.executable();

        final List<String> command = new ArrayList<>(identifiers.size() + 4);
        command.add(exe.toString());
        command.add("--snapshot-file");
        command.add(this.binary.snapshot().toString());
        command.add("--");
        command.addAll(identifiers);

        final Process process;
        try {
            process = new ProcessBuilder(command).start();
        } catch (final Exception e) {
            throw new BotGuardException("Could not start rustypipe-botguard", e);
        }

        final StringBuilder stderr = new StringBuilder();
        final Thread drain = new Thread(new Runnable() {
            @Override
            public void run() {
                stderr.append(readAll(process.getErrorStream()));
            }
        }, "botguard-stderr");
        drain.setDaemon(true);
        drain.start();

        final String stdout = readAll(process.getInputStream());
        try {
            if (!process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new BotGuardException("rustypipe-botguard timed out after " + PROCESS_TIMEOUT_SECONDS + "s");
            }
            drain.join(1000);
        } catch (final InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new BotGuardException("Interrupted while waiting for rustypipe-botguard", e);
        }

        final int exit = process.exitValue();
        if (exit != 0) {
            throw new BotGuardException("rustypipe-botguard exited " + exit + ": " + stderr.toString().trim());
        }
        this.parse(stdout, identifiers, result);
    }

    private void parse(final String stdout, final List<String> identifiers, final Map<String, String> result)
            throws BotGuardException {
        String line = null;
        for (final String candidate : stdout.split("\\r?\\n")) {
            if (!candidate.trim().isEmpty()) {
                line = candidate.trim();
                break;
            }
        }
        if (line == null) {
            throw new BotGuardException("rustypipe-botguard produced no output");
        }

        final String[] fields = line.split("\\s+");
        if (fields.length < identifiers.size()) {
            throw new BotGuardException("Expected " + identifiers.size() + " token(s) but got: " + line);
        }

        long expiry = System.currentTimeMillis() / 1000L + FALLBACK_LIFETIME_SECONDS;
        for (int i = identifiers.size(); i < fields.length; i++) {
            if (fields[i].startsWith("valid_until=")) {
                try {
                    expiry = Long.parseLong(fields[i].substring("valid_until=".length()));
                } catch (final NumberFormatException ignored) {
                }
            }
        }

        for (int i = 0; i < identifiers.size(); i++) {
            final String id = identifiers.get(i);
            final String token = fields[i];
            this.cache.put(id, new CachedToken(token, expiry));
            result.put(id, token);
        }
        LOGGER.debug("rustypipe-botguard minted {} token(s), valid until {}", identifiers.size(), expiry);
    }

    private static String readAll(final InputStream in) {
        try {
            byte[] bytes = org.watermedia.core.tools.DataTool.readAllBytes(in);
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (final Exception e) {
            return "";
        }
    }

    private static final class CachedToken {
        final String value;
        final long expiry;

        CachedToken(String value, long expiry) {
            this.value = value;
            this.expiry = expiry;
        }
    }
}
