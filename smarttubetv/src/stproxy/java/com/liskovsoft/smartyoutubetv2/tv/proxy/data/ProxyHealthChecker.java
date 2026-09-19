package com.liskovsoft.smartyoutubetv2.tv.proxy.data;

import com.liskovsoft.smartyoutubetv2.tv.proxy.MihomoCoreManager;

import java.io.InputStream;
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
            result.dns = checkDns();
            if (result.localProxy) {
                result.youtube = checkHttps("https://www.youtube.com/generate_204");
                result.googleVideo = checkHttps("https://redirector.googlevideo.com/report_mapping?di=no");
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

    private static boolean checkDns() {
        try {
            return InetAddress.getAllByName("www.youtube.com").length > 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean checkHttps(String address) {
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
            InputStream stream = response >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (stream != null) {
                stream.close();
            }
            return response >= 200 && response < 500;
        } catch (Exception ignored) {
            return false;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
}
