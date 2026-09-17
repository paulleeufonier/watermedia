package org.watermedia.api.network.patchs;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.watermedia.WaterMedia;
import org.watermedia.api.network.patchs.kick.KickChannel;
import org.watermedia.api.network.patchs.kick.KickVideo;
import org.watermedia.api.network.patchs.youtube.YtDlpClient;
import org.watermedia.core.tools.DataTool;
import org.watermedia.core.tools.NetTool;

import java.io.*;
import java.net.*;
import java.util.Arrays;
import java.util.List;

public class KickPatch extends AbstractPatch {
    private static final String API_URL = "https://kick.com/api/v2/";
    private static final Gson GSON = new Gson();

    private YtDlpClient ytdlp;

    private synchronized YtDlpClient getYtDlp() {
        if (this.ytdlp == null) {
            this.ytdlp = new YtDlpClient();
        }
        return this.ytdlp;
    }

    @Override
    public String platform() {
        return "Kick";
    }

    @Override
    public boolean isValid(URI uri) {
        String host = uri.getHost();
        String path = uri.getPath();
        return host != null && (host.endsWith(".kick.com") || host.equals("kick.com")) && path != null && !path.isEmpty() && !path.equals("/");
    }

    @Override
    public Result patch(URI uri, Quality prefQuality) throws FixingURLException {
        super.patch(uri, prefQuality);

        String rawPath = uri.getPath();
        if (rawPath == null) throw new FixingURLException(uri, new IllegalArgumentException("Empty path"));
        String path = rawPath.replaceAll("^/+|/+$", "");
        if (path.isEmpty()) throw new FixingURLException(uri, new IllegalArgumentException("Empty channel path"));

        boolean isVideo = path.contains("video/") || path.contains("videos/");

        if (isVideo) {
            String[] split = path.split("/");
            String videoID = split[split.length - 1];
            String streamer = split.length > 2 ? split[0] : null;

            if (streamer != null && !streamer.equalsIgnoreCase("video") && !streamer.equalsIgnoreCase("videos")) {
                try {
                    String streamUrl = getVideoFromChannel(streamer, videoID);
                    if (streamUrl != null) {
                        Result r = new Result(new URI(streamUrl), true, false);
                        r.setExtraOptions(":http-user-agent=" + NetTool.USER_AGENT, ":http-referrer=https://kick.com/", ":network-caching=1500", ":live-caching=1500");
                        return r;
                    }
                } catch (Exception ignored) {}
            }

            try {
                KickVideo video = getVideoInfo(videoID);
                if (video != null && video.url != null) {
                    Result r = new Result(new URI(video.url), true, false);
                    r.setExtraOptions(":http-user-agent=" + NetTool.USER_AGENT, ":http-referrer=https://kick.com/", ":network-caching=1500", ":live-caching=1500");
                    return r;
                }
            } catch (Exception ignored) {}

            try {
                List<String> args = Arrays.asList("--no-playlist", "--no-check-certificates");
                JsonObject info = getYtDlp().info(uri.toString(), args);
                if (info != null && info.has("url")) {
                    String streamUrl = info.get("url").getAsString();
                    Result r = new Result(new URI(streamUrl), true, false);
                    r.setExtraOptions(":http-user-agent=" + NetTool.USER_AGENT, ":http-referrer=https://kick.com/", ":network-caching=1500", ":live-caching=1500");
                    return r;
                }
            } catch (Exception ignored) {}

            throw new FixingURLException(uri, new ConnectException("Kick video not found or unavailable"));
        } else {
            String[] parts = path.split("/");
            String streamerName = (parts[0].equalsIgnoreCase("popout") && parts.length > 1) ? parts[1].trim() : parts[0].trim();

            // 1. Kick API v2
            try {
                KickChannel channel = getChannelInfo(streamerName);
                if (channel != null && channel.url != null && channel.livestream != null && channel.livestream.isStreaming) {
                    Result r = new Result(new URI(channel.url), true, true);
                    r.setExtraOptions(":http-user-agent=" + NetTool.USER_AGENT, ":http-referrer=https://kick.com/", ":network-caching=1500", ":live-caching=1500");
                    return r;
                }
            } catch (Exception ignored) {}

            // 2. Scrape channel page
            try {
                KickChannel fallback = scrapeChannel(streamerName);
                if (fallback != null && fallback.url != null && fallback.livestream != null && fallback.livestream.isStreaming) {
                    Result r = new Result(new URI(fallback.url), true, true);
                    r.setExtraOptions(":http-user-agent=" + NetTool.USER_AGENT, ":http-referrer=https://kick.com/", ":network-caching=1500", ":live-caching=1500");
                    return r;
                }
            } catch (Exception ignored) {}

            // 3. Fallback to yt-dlp
            try {
                List<String> args = Arrays.asList("--no-playlist", "--no-check-certificates");
                JsonObject info = getYtDlp().info(uri.toString(), args);
                if (info != null && info.has("url")) {
                    String streamUrl = info.get("url").getAsString();
                    boolean isLive = info.has("is_live") && info.get("is_live").getAsBoolean();
                    Result r = new Result(new URI(streamUrl), true, isLive);
                    r.setExtraOptions(":http-user-agent=" + NetTool.USER_AGENT, ":http-referrer=https://kick.com/", ":network-caching=1500", ":live-caching=1500");
                    return r;
                }
            } catch (Exception ignored) {}

            throw new FixingURLException(uri, new ConnectException("Kick streamer is offline or unavailable"));
        }
    }

    public String getVideoFromChannel(String streamer, String videoId) throws Exception {
        try (InputStreamReader in = new InputStreamReader(getInputStream(new URI(API_URL + "channels/" + streamer + "/videos")))) {
            JsonArray array = GSON.fromJson(in, JsonArray.class);
            if (array != null) {
                for (JsonElement el : array) {
                    if (el.isJsonObject()) {
                        JsonObject obj = el.getAsJsonObject();
                        if (obj.has("video") && obj.get("video").isJsonObject()) {
                            JsonObject vObj = obj.getAsJsonObject("video");
                            if (vObj.has("uuid") && videoId.equalsIgnoreCase(vObj.get("uuid").getAsString())) {
                                if (obj.has("source")) return obj.get("source").getAsString();
                            }
                        }
                    }
                }
            }
        }
        return null;
    }

    public KickChannel getChannelInfo(String channel) throws Exception {
        try (InputStreamReader in = new InputStreamReader(getInputStream(new URI(API_URL + "channels/" + channel)))) {
            return GSON.fromJson(in, KickChannel.class);
        } catch (Exception e) {
            KickChannel fallback = scrapeChannel(channel);
            if (fallback != null) return fallback;
            throw e;
        }
    }

    public KickVideo getVideoInfo(String videoId) throws Exception {
        try (InputStreamReader in = new InputStreamReader(getInputStream(new URI("https://kick.com/api/v1/video/" + videoId)))) {
            return GSON.fromJson(in, KickVideo.class);
        } catch (Exception e) {
            KickVideo fallback = scrapeVideo(videoId);
            if (fallback != null) return fallback;
            throw e;
        }
    }

    public InputStream getInputStream(URI url) throws IOException {
        HttpURLConnection conn = NetTool.connectToHTTP(url, "GET");
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36");
        conn.setRequestProperty("Accept", "application/json, text/plain, */*");
        conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
        conn.setRequestProperty("Referer", "https://kick.com/");
        conn.setRequestProperty("sec-ch-ua", "\"Not A(Brand\";v=\"8\", \"Chromium\";v=\"132\", \"Google Chrome\";v=\"132\"");
        conn.setRequestProperty("sec-ch-ua-mobile", "?0");
        conn.setRequestProperty("sec-ch-ua-platform", "\"Linux\"");
        conn.setRequestProperty("sec-fetch-dest", "empty");
        conn.setRequestProperty("sec-fetch-mode", "cors");
        conn.setRequestProperty("sec-fetch-site", "same-origin");
        try {
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw new ConnectException(String.format("Server url %s response with status code (%s): %s", url, conn.getResponseCode(), conn.getResponseMessage()));
            }
            return new ByteArrayInputStream(DataTool.readAllBytes(conn.getInputStream()));
        } finally {
            conn.disconnect();
        }
    }

    private KickChannel scrapeChannel(String channel) {
        try {
            HttpURLConnection conn = NetTool.connectToHTTP(new URI("https://kick.com/" + channel), "GET");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36");
            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
            conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
            conn.setRequestProperty("Referer", "https://kick.com/");
            if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
                String html = new String(DataTool.readAllBytes(conn.getInputStream()), "UTF-8");
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"playback_url\"\\s*:\\s*\"([^\"]+)\"").matcher(html);
                if (m.find()) {
                    KickChannel kc = new KickChannel();
                    kc.url = m.group(1).replace("\\/", "/");
                    kc.username = channel;
                    kc.livestream = new KickChannel.isLive();
                    kc.livestream.isStreaming = html.contains("\"is_live\":true") || html.contains("\"is_live\": true");
                    return kc;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private KickVideo scrapeVideo(String videoId) {
        try {
            HttpURLConnection conn = NetTool.connectToHTTP(new URI("https://kick.com/video/" + videoId), "GET");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36");
            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
            conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
            conn.setRequestProperty("Referer", "https://kick.com/");
            if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
                String html = new String(DataTool.readAllBytes(conn.getInputStream()), "UTF-8");
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"source\"\\s*:\\s*\"([^\"]+)\"").matcher(html);
                if (m.find()) {
                    KickVideo kv = new KickVideo();
                    kv.url = m.group(1).replace("\\/", "/");
                    return kv;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
