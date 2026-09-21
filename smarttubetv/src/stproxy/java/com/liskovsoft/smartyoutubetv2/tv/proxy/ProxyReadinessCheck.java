package com.liskovsoft.smartyoutubetv2.tv.proxy;

/** Serialized cleanup then a fresh HTTPS delay test. Cached latency is never readiness. */
final class ProxyReadinessCheck {
    interface Current { boolean isCurrent(); }
    interface Result { void complete(String data, String error); }
    interface Step { void run(Result result); }

    static void run(Current current, Step cleanup, Step probe, Result result) {
        cleanup.run((ignored, error) -> {
            if (!current.isCurrent()) { result.complete(null, "操作已取消"); return; }
            if (error != null) { result.complete(null, "旧连接清理失败，请重新连接"); return; }
            probe.run((delay, probeError) -> {
                if (!current.isCurrent()) { result.complete(null, "操作已取消"); return; }
                if (probeError != null || !isPositiveDelay(delay)) {
                    result.complete(null, "当前节点未通过 YouTube 连通测速，请重试或切换节点");
                } else {
                    result.complete(delay, null);
                }
            });
        });
    }

    private static boolean isPositiveDelay(String value) {
        try { return Integer.parseInt(value) > 0; }
        catch (NumberFormatException ignored) { return false; }
    }
}
