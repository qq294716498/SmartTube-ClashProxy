package com.liskovsoft.smartyoutubetv2.tv.proxy.data;

import com.liskovsoft.smartyoutubetv2.tv.proxy.MihomoCoreManager;
import com.liskovsoft.smartyoutubetv2.tv.proxy.ProxyErrors;
import com.liskovsoft.smartyoutubetv2.common.proxy.EmbeddedProxyRoute;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Small, privacy-safe connectivity checks used by the TV diagnostics screen. */
public final class ProxyHealthChecker {
    public interface Callback {
        void onResult(Result result);
    }

    public static final class Result {
        public boolean localProxy;
        public boolean youtube;
        public boolean googleVideo;
        public boolean dns;
        public String youtubeDetail;
        public String googleVideoDetail;
        public String googleVideoSource = "视频域名";
    }

    private static final class Probe {
        final boolean passed;
        final String detail;

        Probe(boolean passed, String detail) {
            this.passed = passed;
            this.detail = detail;
        }
    }

    private static final int TIMEOUT_MS = 5_000;
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "smarttube-proxy-health");
        thread.setDaemon(true);
        return thread;
    });

    private ProxyHealthChecker() {
    }

    public static void check(Callback callback) {
        EXECUTOR.execute(() -> {
            Result result = new Result();
            result.localProxy = checkLocalProxy();
            // Local DNS does not measure DNS through Mihomo and can block without a timeout.
            result.dns = false;
            if (result.localProxy) {
                // Probe via the very same OkHttp factory used for video segments.
                Probe youtube = checkHttps("https://www.youtube.com/generate_204", true);
                result.youtube = youtube.passed;
                result.youtubeDetail = youtube.detail;
                String mediaHost = EmbeddedProxyRoute.getLastMediaHost();
                boolean actualMediaHost = mediaHost != null;
                result.googleVideoSource = actualMediaHost ? "最近播放域名" : "固定探测地址";
                // The fixed redirector does not serve the actual video stream. Once a
                // video was attempted, prefer the CDN host used by that stream.
                Probe video = checkHttps(actualMediaHost
                        ? "https://" + mediaHost + "/generate_204"
                        : "https://redirector.googlevideo.com/report_mapping?di=no", false);
                result.googleVideo = video.passed;
                result.googleVideoDetail = video.detail;
                MihomoCoreManager.recordDiagnosticEvent("视频域名探测（"
                        + result.googleVideoSource + "）：" + video.detail);
            }
            callback.onResult(result);
        });
    }

    private static boolean checkLocalProxy() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(
                    MihomoCoreManager.LOOPBACK_HOST, MihomoCoreManager.MIXED_PORT), 500);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static Probe checkHttps(String address, boolean expectNoContent) {
        try {
            // A 403/404 is still proof that the TLS connection succeeded. The
            // probe path intentionally does not include a signed playback URL.
            int status = EmbeddedProxyRoute.probeHttps(address, TIMEOUT_MS);
            return new Probe(expectNoContent ? status == 204 : true,
                    "TLS已建立，HTTP " + status);
        } catch (Exception error) {
            String message = ProxyErrors.redact(error.getMessage());
            return new Probe(false, error.getClass().getSimpleName()
                    + (message == null || message.isEmpty() ? "" : "：" + message));
        }
    }
}
