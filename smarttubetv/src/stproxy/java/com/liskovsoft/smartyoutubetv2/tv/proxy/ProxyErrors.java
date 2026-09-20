package com.liskovsoft.smartyoutubetv2.tv.proxy;

/** Shared boundary for UI and diagnostics: never print subscription credentials. */
public final class ProxyErrors {
    private ProxyErrors() { }
    public static String redact(String value) {
        if (value == null) return null;
        String safe = value.replaceAll("(?i)https?://[^\\s\\\"<>]+", "[订阅地址已隐藏]")
                .replaceAll("(?i)(token|password|secret|uuid|authorization)([\\s\\\"']*[:=][\\s\\\"']*)[^\\s,}\\\"']+", "$1$2***");
        return safe.length() > 600 ? safe.substring(0, 600) + "…" : safe;
    }
}
