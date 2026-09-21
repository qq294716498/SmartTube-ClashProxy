package com.liskovsoft.smartyoutubetv2.common.app.models.errors;

import android.content.Context;
import com.liskovsoft.smartyoutubetv2.common.proxy.EmbeddedProxySettingsBridge;

/** A recoverable proxy problem, rather than a raw Retrofit exception. */
public final class ProxyConnectionError implements ErrorFragmentData {
    private final Context context;
    private final String message;

    public ProxyConnectionError(Context context, String message) {
        this.context = context;
        this.message = message;
    }
    @Override public String getMessage() { return message; }
    @Override public String getActionText() { return "网络代理设置"; }
    @Override public void onAction() { EmbeddedProxySettingsBridge.show(context); }
}
