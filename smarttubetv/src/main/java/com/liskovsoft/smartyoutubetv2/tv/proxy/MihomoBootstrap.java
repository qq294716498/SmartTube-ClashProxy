package com.liskovsoft.smartyoutubetv2.tv.proxy;

import android.content.Context;
import android.util.Log;

import com.liskovsoft.sharedutils.locale.LocaleUpdater;
import com.liskovsoft.smartyoutubetv2.tv.BuildConfig;
import com.liskovsoft.smartyoutubetv2.common.proxy.EmbeddedProxyRoute;

import java.lang.reflect.Method;

/**
 * Flavor-safe entry point for the embedded proxy core.
 *
 * The real implementation only exists in the stproxy source set, so the
 * official SmartTube flavors never link or package libmihomo.
 */
public final class MihomoBootstrap {
    private static final String TAG = "MihomoBootstrap";
    private static final String MANAGER_CLASS =
            "com.liskovsoft.smartyoutubetv2.tv.proxy.MihomoCoreManager";

    private MihomoBootstrap() {
    }

    public static void start(Context context) {
        if (!BuildConfig.MIHOMO_EMBEDDED) {
            return;
        }
        EmbeddedProxyRoute.install(context.getApplicationContext());

        // 优兔喵视频是自用简体中文版。只固定 stproxy 风味，
        // 不改变上游普通版和测试版的多语言行为。
        LocaleUpdater locale = new LocaleUpdater(context);
        if (!"zh_CN".equals(locale.getPreferredLanguage())) {
            locale.setPreferredLanguage("zh_CN");
        }
        if (!"CN".equals(locale.getPreferredCountry())) {
            locale.setPreferredCountry("CN");
        }

        try {
            Class<?> manager = Class.forName(MANAGER_CLASS);
            Method start = manager.getMethod("startIfEnabled", Context.class);
            start.invoke(null, context.getApplicationContext());
        } catch (Throwable error) {
            // Proxy startup must never crash SmartTube. The diagnostics UI will
            // surface this state in a later phase without exposing user secrets.
            Log.e(TAG, "Embedded Mihomo startup failed", error);
        }
    }
}
