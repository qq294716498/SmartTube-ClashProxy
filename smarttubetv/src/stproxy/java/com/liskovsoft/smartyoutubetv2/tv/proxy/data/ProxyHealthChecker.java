package com.liskovsoft.smartyoutubetv2.tv.proxy.data;

import com.liskovsoft.smartyoutubetv2.tv.proxy.MihomoCoreManager;
import com.liskovsoft.smartyoutubetv2.tv.proxy.ProxyErrors;

import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.URL;
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
                Probe youtube = checkHttps("https://www.youtube.com/generate_204", true);
                result.youtube = youtube.passed;
                result.youtubeDetail = youtube.detail;
                Probe video = checkHttps("https://redirector.googlevideo.com/report_mapping?di=no", false);
                result.googleVideo = video.passed;
                result.googleVideoDetail = video.detail;
                MihomoCoreManager.recordDiagnosticEvent("视频域名探测：" + video.detail);
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
        HttpURLConnection connection = null;
        try {
            Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(
                    MihomoCoreManager.LOOPBACK_HOST, MihomoCoreManager.MIXED_PORT));
            connection = (HttpURLConnection) new URL(address).openConnection(proxy);
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            int response = connection.getResponseCode();
            // Any HTTP response means CONNECT and TLS succeeded. A 403/404 on
            // this fixed probe URL says nothing about real video stream URLs.
            return new Probe(expectNoContent ? response == 204 : response > 0,
                    "TLS已建立，HTTP " + response);
        } catch (Exception error) {
            String message = ProxyErrors.redact(error.getMessage());
            return new Probe(false, error.getClass().getSimpleName()
                    + (message == null || message.isEmpty() ? "" : "：" + message));
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
}
