package org.watermedia.api.network.patchs;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.api.network.patchs.youtube.BotGuardClient;
import org.watermedia.api.network.patchs.youtube.BotGuardException;
import org.watermedia.api.network.patchs.youtube.YoutubeVisitorData;
import org.watermedia.api.network.patchs.youtube.YtDlpClient;

import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import static org.watermedia.WaterMedia.LOGGER;

public class YoutubePatch extends AbstractPatch {
    public static final String NAME = "Youtube";
    private static final Marker IT = MarkerManager.getMarker(YoutubePatch.class.getSimpleName());
    private static final List<String> HOSTS = Arrays.asList("youtube.com", "youtu.be");
    private static final Pattern PATTERN = Pattern.compile("(?:youtu\\.be/|youtube\\.com/(?:embed/|v/|shorts/|feeds/api/videos/|watch\\?v=|watch\\?.+&v=))([^/?&#]+)");

    private final YtDlpClient ytdlp;
    private final BotGuardClient botGuard;

    public YoutubePatch() {
        this.ytdlp = new YtDlpClient();
        this.botGuard = new BotGuardClient();
    }

    @Override
    public String platform() {
        return NAME;
    }

    @Override
    public boolean isValid(URI uri) {
        String host = uri.getHost();
        if (host == null) return false;
        host = host.toLowerCase(Locale.ROOT);
        boolean matchesHost = false;
        for (String h : HOSTS) {
            if (host.equals(h) || host.endsWith("." + h)) {
                matchesHost = true;
                break;
            }
        }
        return matchesHost && PATTERN.matcher(uri.toString()).find();
    }

    @Override
    public Result patch(URI uri, Quality preferQuality) throws FixingURLException {
        super.patch(uri, preferQuality);

        final List<String> args = new ArrayList<>();
        if (PATTERN.matcher(uri.toString()).find()) {
            args.add("--no-playlist");
        }
        // Force android player client to bypass YouTube 403 Forbidden throttling!
        args.add("--extractor-args");
        args.add("youtube:player_client=android");

        // Prefer combined format (both video and audio) so LibVLC can play without slaves
        args.add("-f");
        args.add("b[protocol^=m3u8]/best[protocol^=m3u8]/b/best[vcodec!=none][acodec!=none]/bv*+ba/b");

        try {
            JsonObject media;
            try {
                media = this.ytdlp.info(uri.toString(), args);
            } catch (final BotGuardException e) {
                if (isBotCheck(e.getMessage())) {
                    media = retryWithPoToken(uri, args);
                } else {
                    throw e;
                }
            }

            Result res = extractResult(media);
            String ua = extractUserAgent(media);
            if (!isAccessible(res.uri.toString(), ua)) {
                LOGGER.warn(IT, "Stream {} returned 403 Forbidden, retrying with fresh BotGuard po_token...", res.uri);
                media = retryWithPoToken(uri, args);
                res = extractResult(media);
            }
            return res;
        } catch (Exception e) {
            throw new FixingURLException(uri, e);
        }
    }

    private JsonObject retryWithPoToken(final URI uri, final List<String> baseArgs) throws Exception {
        try {
            LOGGER.info(IT, "Request blocked by YouTube anti-bot, minting fresh BotGuard po_token...");
            final String visitorData = YoutubeVisitorData.fetch();
            final String token = this.botGuard.mint(visitorData);
            final List<String> args = new ArrayList<>(baseArgs);
            args.add("--extractor-args");
            args.add("youtube:po_token=web.gvs+" + token + ",web_safari.gvs+" + token
                    + ",mweb.gvs+" + token + ",tv.gvs+" + token
                    + ";visitor_data=" + visitorData + ";player_skip=webpage,configs");
            args.add("--extractor-args");
            args.add("youtubetab:skip=webpage");
            LOGGER.info(IT, "Retrying '{}' with BotGuard po_token", uri);
            return this.ytdlp.info(uri.toString(), args);
        } catch (final Exception retry) {
            LOGGER.error(IT, "Failed to retry with po_token: {}", retry.getMessage());
            throw retry;
        }
    }

    private Result extractResult(JsonObject media) throws Exception {
        boolean isLive = bool(media, "is_live") || bool(media, "was_live");

        // 1. Check if top-level has direct URL
        String directUrl = str(media, "url");
        if (directUrl != null && !directUrl.isEmpty()) {
            Result r = new Result(new URI(directUrl), true, isLive);
            r.setExtraOptions(":http-user-agent=" + extractUserAgent(media), ":network-caching=4000", ":live-caching=4000");
            return r;
        }

        // 2. Inspect formats array
        JsonArray formats = media.getAsJsonArray("formats");
        if (formats != null && formats.size() > 0) {
            JsonObject bestCombined = null;
            int bestCombinedHeight = -1;

            JsonObject bestVideoOnly = null;
            int bestVideoHeight = -1;

            JsonObject bestAudioOnly = null;
            double bestAudioBitrate = -1;

            for (int i = 0; i < formats.size(); i++) {
                JsonObject f = formats.get(i).getAsJsonObject();
                String fUrl = str(f, "url");
                if (fUrl == null || fUrl.isEmpty()) continue;
                if ("mhtml".equals(str(f, "protocol"))) continue; // storyboards

                boolean hasVideo = !"none".equals(str(f, "vcodec")) && f.get("vcodec") != null;
                boolean hasAudio = !"none".equals(str(f, "acodec")) && f.get("acodec") != null;
                int height = intOr(f, "height", 0);
                double abr = dbl(f, "abr");

                if (hasVideo && hasAudio) {
                    if (bestCombined == null || height >= bestCombinedHeight) {
                        bestCombined = f;
                        bestCombinedHeight = height;
                    }
                } else if (hasVideo) {
                    if (bestVideoOnly == null || height > bestVideoHeight) {
                        bestVideoOnly = f;
                        bestVideoHeight = height;
                    }
                } else if (hasAudio) {
                    if (bestAudioOnly == null || abr > bestAudioBitrate) {
                        bestAudioOnly = f;
                        bestAudioBitrate = abr;
                    }
                }
            }

            if (bestCombined != null) {
                Result r = new Result(new URI(str(bestCombined, "url")), true, isLive);
                r.setExtraOptions(":http-user-agent=" + extractUserAgent(bestCombined), ":network-caching=4000", ":live-caching=4000");
                return r;
            }

            if (bestVideoOnly != null) {
                Result r = new Result(new URI(str(bestVideoOnly, "url")), true, isLive);
                if (bestAudioOnly != null) {
                    r.setAudioTrack(new URI(str(bestAudioOnly, "url")));
                }
                r.setExtraOptions(":http-user-agent=" + extractUserAgent(bestVideoOnly), ":network-caching=4000", ":live-caching=4000");
                return r;
            }

            if (bestAudioOnly != null) {
                Result r = new Result(new URI(str(bestAudioOnly, "url")), false, isLive);
                r.setExtraOptions(":http-user-agent=" + extractUserAgent(bestAudioOnly), ":network-caching=4000", ":live-caching=4000");
                return r;
            }
        }

        throw new IllegalStateException("No playable format found in yt-dlp response");
    }

    private static String extractUserAgent(JsonObject f) {
        if (f != null && f.has("http_headers") && f.get("http_headers").isJsonObject()) {
            JsonObject hh = f.getAsJsonObject("http_headers");
            if (hh.has("User-Agent")) return hh.get("User-Agent").getAsString();
        }
        return org.watermedia.core.tools.NetTool.USER_AGENT;
    }

    private static boolean isBotCheck(final String message) {
        if (message == null) return false;
        final String m = message.toLowerCase(Locale.ROOT);
        return m.contains("not a bot") || m.contains("sign in to confirm") || m.contains("po_token")
                || m.contains("confirm your age");
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return (e == null || e.isJsonNull()) ? null : e.getAsString();
    }

    private static double dbl(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return (e == null || e.isJsonNull()) ? 0d : e.getAsDouble();
    }

    private static boolean bool(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && !e.isJsonNull() && e.getAsBoolean();
    }

    private static int intOr(JsonObject o, String key, int def) {
        JsonElement e = o.get(key);
        return (e == null || e.isJsonNull()) ? def : e.getAsInt();
    }

    private static boolean isAccessible(String urlStr, String userAgent) {
        if (urlStr == null || urlStr.isEmpty()) return false;
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", userAgent != null ? userAgent : org.watermedia.core.tools.NetTool.USER_AGENT);
            conn.setRequestProperty("Range", "bytes=0-1024");
            conn.setConnectTimeout(2500);
            conn.setReadTimeout(2500);
            int code = conn.getResponseCode();
            conn.disconnect();
            return code != 403;
        } catch (Exception ignored) {
            return true;
        }
    }
}
