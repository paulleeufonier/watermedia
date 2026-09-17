package org.watermedia.api.network.patchs.youtube;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.watermedia.core.tools.DataTool;
import org.watermedia.core.tools.NetTool;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;

public final class YoutubeVisitorData {
    private static final String ENDPOINT = "https://www.youtube.com/youtubei/v1/visitor_id?prettyPrint=false";
    private static final String CLIENT_VERSION = "2.20250601.01.00";
    private static final String BODY =
            "{\"context\":{\"client\":{\"clientName\":\"WEB\",\"clientVersion\":\"" + CLIENT_VERSION
                    + "\",\"hl\":\"en\",\"gl\":\"US\"}}}";

    private YoutubeVisitorData() {}

    public static String fetch() throws Exception {
        HttpURLConnection conn = NetTool.connectToHTTP(URI.create(ENDPOINT), "POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("X-YouTube-Client-Name", "1");
        conn.setRequestProperty("X-YouTube-Client-Version", CLIENT_VERSION);
        conn.setRequestProperty("User-Agent", NetTool.USER_AGENT);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(BODY.getBytes(StandardCharsets.UTF_8));
        }

        try {
            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("visitor_id endpoint returned HTTP " + code);
            }
            byte[] bytes = DataTool.readAllBytes(conn.getInputStream());
            String responseStr = new String(bytes, StandardCharsets.UTF_8);
            JsonObject json = new JsonParser().parse(responseStr).getAsJsonObject();
            return json.getAsJsonObject("responseContext").get("visitorData").getAsString();
        } finally {
            conn.disconnect();
        }
    }
}
