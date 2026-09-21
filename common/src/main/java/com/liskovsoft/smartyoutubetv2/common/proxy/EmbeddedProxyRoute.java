package com.liskovsoft.smartyoutubetv2.common.proxy;

import android.content.Context;
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
import okhttp3.OkHttpClient;
import okhttp3.Protocol;

/** Flavor-safe media transport. Every new request observes the current route. */
public final class EmbeddedProxyRoute {
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
                        .protocols(Collections.singletonList(Protocol.HTTP_1_1))
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
        if (enabled) EmbeddedProxyStartup.connecting();
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

    /** Also used after a node switch so existing media sockets do not keep the old node. */
    public static synchronized void refresh() {
        epoch++;
        if (client != null) {
            client.dispatcher().cancelAll();
            client.connectionPool().evictAll();
            client = null;
        }
        if (installed) {
            // Retrofit's lazy singleton survives OkHttpManager.unhold(). Clear
            // its retained pool as well, preserving authentication interceptors.
            OkHttpClient api = RetrofitOkHttpHelper.getClient();
            api.dispatcher().cancelAll();
            api.connectionPool().evictAll();
            OkHttpClient shared = OkHttpManager.instance().getClient();
            shared.dispatcher().cancelAll();
            shared.connectionPool().evictAll();
        }
    }

    public static Call.Factory callFactory() {
        return FACTORY;
    }
}
