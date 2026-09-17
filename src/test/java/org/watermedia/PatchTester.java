package org.watermedia;

import org.watermedia.api.network.NetworkAPI;
import org.watermedia.api.network.patchs.AbstractPatch;
import org.watermedia.loaders.ILoader;

import java.net.URI;
import java.util.Arrays;

public class PatchTester {
    public static void main(String[] args) throws Exception {
        try {
            WaterMedia.prepare(ILoader.DEFAULT);
        } catch (Exception ignored) {}
        new NetworkAPI().start(ILoader.DEFAULT);
        String target = args.length > 0 ? args[0] : "https://www.youtube.com/watch?v=dQw4w9WgXcQ";
        System.out.println("[WATERMeDIA] Testing URL resolution for: " + target);

        URI uri = URI.create(target);
        AbstractPatch.Result result = NetworkAPI.patch(uri);
        if (result == null) {
            System.err.println("[WATERMeDIA] Result is null! No patch matched or resolution failed.");
            System.exit(1);
        }

        System.out.println("[WATERMeDIA] === RESOLUTION SUCCESSFUL ===");
        System.out.println("[WATERMeDIA] Direct Stream URL: " + result.uri);
        if (result.audioUrl != null) {
            System.out.println("[WATERMeDIA] Audio Slave URL:  " + result.audioUrl);
        }
        System.out.println("[WATERMeDIA] Assume Stream:    " + result.assumeStream);
        System.out.println("[WATERMeDIA] Assume Video:     " + result.assumeVideo);
        if (result.extraOptions != null && result.extraOptions.length > 0) {
            System.out.println("[WATERMeDIA] VLC Options:      " + Arrays.toString(result.extraOptions));
        }
        System.out.println("[WATERMeDIA] =============================");
    }
}
