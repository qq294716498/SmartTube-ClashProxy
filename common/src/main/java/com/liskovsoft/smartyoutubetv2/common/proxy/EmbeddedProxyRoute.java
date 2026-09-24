package com.liskovsoft.smartyoutubetv2.common.proxy;

import android.content.Context;
import android.util.Log;
import com.liskovsoft.googlecommon.common.helpers.RetrofitOkHttpHelper;
import com.liskovsoft.sharedutils.okhttp.OkHttpManager;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;

/** Flavor-safe media transport. Every new request observes the current route. */
public final class EmbeddedProxyRoute {
    private static final String TAG = "EmbeddedProxyRoute";
    private static boolean enabled;
    private static long epoch;
    private static OkHttpClient client;
    private static boolean installed;
    private static final java.net.Proxy LOCAL_PROXY = new java.net.Proxy(
            java.net.Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", 7890));
    private static final ProxySelector SELECTOR = new ProxySelector() {
        @Override public List<java.net.Proxy> select(URI uri) {
            if (uri == null) throw new IllegalArgumentException("uri == null");
            String host = uri.getHost();
            boolean local = "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host)
                    || "::1".equals(host) || "[::1]".equals(host);
            boolean web = "http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme());
            return Collections.singletonList(isEnabled() && web && !local
                    ? LOCAL_PROXY : java.net.Proxy.NO_PROXY);
        }

        @Override public void connectFailed(URI uri, SocketAddress address, IOException error) {
            // No direct fallback when the user has enabled the embedded proxy.
        }
    };
    private static final Call.Factory FACTORY = request -> {
        synchronized (EmbeddedProxyRoute.class) {
            if (client == null) {
                client = new OkHttpClient.Builder()
                        // Keep OkHttp's default protocol negotiation so HTTPS video
                        // connections through Mihomo can use HTTP/2 when supported.
                        // Forcing HTTP/1.1 amplifies high-latency node round trips and
                        // becomes especially visible while loading 2K/4K segments.
                        .connectionPool(new ConnectionPool(10, 5, TimeUnit.MINUTES))
                        .proxy(enabled
                                ? new java.net.Proxy(java.net.Proxy.Type.HTTP,
                                        new InetSocketAddress("127.0.0.1", 7890))
                                : java.net.Proxy.NO_PROXY)
                        .connectTimeout(20, TimeUnit.SECONDS)
                        .readTimeout(20, TimeUnit.SECONDS)
                        .writeTimeout(20, TimeUnit.SECONDS)
                        .build();
            }
            return client.newCall(request);
        }
    };

    private EmbeddedProxyRoute() { }

    public static boolean isSupported(Context context) {
        return "app.smarttube.proxy".equals(context.getPackageName());
    }

    /** Install before creating API clients: OkHttp retains the selector object. */
    public static synchronized void install(Context context) {
        if (installed || !isSupported(context)) return;
        enabled = context.getSharedPreferences("smarttube_embedded_proxy", Context.MODE_PRIVATE)
                .getBoolean("enabled", false);
        ProxySelector.setDefault(SELECTOR);
        installed = true;
    }

    public static synchronized boolean isEnabled() {
        return enabled;
    }

    public static synchronized long getEpoch() {
        return epoch;
    }

    public static synchronized void setEnabled(boolean value) {
        if (enabled == value) return;
        enabled = value;
        refresh();
    }

    /** Hard route change: cancel requests that might still be using the old direct path. */
    public static synchronized void refresh() {
        refreshConnections(true);
    }

    /** After a proxy node change, let active requests finish and retire idle sockets. */
    public static synchronized void refreshAfterNodeChange() {
        refreshConnections(false);
    }

    private static void refreshConnections(boolean cancelActive) {
        epoch++;
        if (client != null) {
            closeClient("embedded", client, cancelActive);
            client = null;
        }
        if (installed) {
            // Retrofit's lazy singleton survives OkHttpManager.unhold(). Clear
            // its retained pool as well, preserving authentication interceptors.
            // Some Android TV builds initialize these clients lazily. Route changes
            // must remain best-effort and must never terminate the UI process.
            try {
                closeClient("retrofit", RetrofitOkHttpHelper.getClient(), cancelActive);
            } catch (Throwable error) {
                Log.w(TAG, "Unable to refresh Retrofit connections", error);
            }
            try {
                closeClient("shared", OkHttpManager.instance().getClient(), cancelActive);
            } catch (Throwable error) {
                Log.w(TAG, "Unable to refresh shared connections", error);
            }
        }
    }

    private static void closeClient(String name, OkHttpClient target, boolean cancelActive) {
        if (target == null) return;
        try {
            if (cancelActive) target.dispatcher().cancelAll();
            target.connectionPool().evictAll();
        } catch (Throwable error) {
            Log.w(TAG, "Unable to refresh " + name + " connections", error);
        }
    }

    public static Call.Factory callFactory() {
        return FACTORY;
    }
}
