package com.liskovsoft.smartyoutubetv2.common.proxy;

import android.content.Context;

import java.lang.reflect.Method;

/** Flavor-safe bridge to settings implemented only by SmartTube Proxy. */
public final class EmbeddedProxySettingsBridge {
    private static final String PRESENTER =
            "com.liskovsoft.smartyoutubetv2.tv.proxy.ui.ProxySettingsPresenter";

    private EmbeddedProxySettingsBridge() {
    }

    public static boolean isAvailable() {
        try {
            Class.forName(PRESENTER);
            return true;
        } catch (ClassNotFoundException ignored) {
            return false;
        }
    }

    public static void show(Context context) {
        try {
            Class<?> presenter = Class.forName(PRESENTER);
            Method method = presenter.getMethod("show", Context.class);
            method.invoke(null, context);
        } catch (Throwable ignored) {
            // Official flavors intentionally do not contain the implementation.
        }
    }

    public static String getHomeLabel(Context context) {
        try {
            Class<?> presenter = Class.forName(PRESENTER);
            Method method = presenter.getMethod("getHomeLabel", Context.class);
            Object result = method.invoke(null, context);
            return result instanceof String ? (String) result : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 0 = off, 1 = connecting, 2 = running, 3 = failed.
     */
    public static int getHomeState(Context context) {
        try {
            Class<?> presenter = Class.forName(PRESENTER);
            Method method = presenter.getMethod("getHomeState", Context.class);
            Object result = method.invoke(null, context);
            return result instanceof Integer ? (Integer) result : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }
}
