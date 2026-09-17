package org.watermedia.api.network.patchs;

import com.google.gson.JsonObject;
import org.watermedia.WaterMedia;
import org.watermedia.api.network.patchs.twitch.StreamQuality;
import org.watermedia.api.network.patchs.twitch.TwitchAPI;
import org.watermedia.api.network.patchs.youtube.YtDlpClient;
import org.watermedia.core.tools.NetTool;

import java.net.*;
import java.util.*;

public class TwitchPatch extends AbstractPatch {
    static {
        CookieHandler.setDefault(new CookieManager(null, CookiePolicy.ACCEPT_ALL));
    }

    private YtDlpClient ytdlp;

    private synchronized YtDlpClient getYtDlp() {
        if (this.ytdlp == null) {
            this.ytdlp = new YtDlpClient();
        }
        return this.ytdlp;
    }

    @Override
    public String platform() {
        return "Twitch";
    }

    @Override
    public boolean isValid(URI uri) {
        String host = uri.getHost();
        String path = uri.getPath();
        return host != null && (host.equals("www.twitch.tv") || host.equals("twitch.tv")) && path != null && !path.isEmpty() && !path.equals("/");
    }

    @Override
    public Result patch(URI uri, Quality preferQuality) throws FixingURLException {
        super.patch(uri, preferQuality);
        String rawPath = uri.getPath();
        if (rawPath == null) throw new FixingURLException(uri, new IllegalArgumentException("Empty path"));
        String path = rawPath.replaceAll("^/+|/+$", "");
        if (path.isEmpty()) throw new FixingURLException(uri, new IllegalArgumentException("Empty channel path"));

        boolean isVod = path.startsWith("videos/");
        String id = isVod ? path.substring(7) : path.split("/")[0].toLowerCase(Locale.ROOT).trim();

        // 1. Try TwitchAPI (fast GQL + Usher)
        try {
            List<StreamQuality> qualities = isVod ? TwitchAPI.getVod(id) : TwitchAPI.getStream(id);
            if (qualities != null && !qualities.isEmpty()) {
                Result r = new Result(new URI(qualities.get(0).getUrl()), true, !isVod);
                r.setExtraOptions(":http-user-agent=" + NetTool.USER_AGENT, ":http-referrer=https://www.twitch.tv/", ":network-caching=1500", ":live-caching=1500");
                return r;
            }
        } catch (Exception e) {
            WaterMedia.LOGGER.debug("TwitchAPI failed for {}, attempting yt-dlp fallback: {}", id, e.getMessage());
        }

        // 2. Try yt-dlp fallback
        try {
            List<String> args = Arrays.asList("--no-playlist", "--no-check-certificates");
            JsonObject info = getYtDlp().info(uri.toString(), args);
            if (info != null && info.has("url")) {
                String streamUrl = info.get("url").getAsString();
                boolean isLive = info.has("is_live") && info.get("is_live").getAsBoolean();
                Result r = new Result(new URI(streamUrl), true, isLive || !isVod);
                r.setExtraOptions(":http-user-agent=" + NetTool.USER_AGENT, ":http-referrer=https://www.twitch.tv/", ":network-caching=1500", ":live-caching=1500");
                return r;
            }
        } catch (Exception e) {
            WaterMedia.LOGGER.warn("yt-dlp Twitch fallback also failed for {}: {}", uri, e.getMessage());
        }

        throw new FixingURLException(uri, new ConnectException("Twitch stream or video is offline or unavailable"));
    }
}