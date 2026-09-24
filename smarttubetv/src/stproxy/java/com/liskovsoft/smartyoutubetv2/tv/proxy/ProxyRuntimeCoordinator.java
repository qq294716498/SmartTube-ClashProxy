package com.liskovsoft.smartyoutubetv2.tv.proxy;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import com.liskovsoft.sharedutils.okhttp.OkHttpManager;
import com.liskovsoft.smartyoutubetv2.common.prefs.GeneralData;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerTweaksData;
import com.liskovsoft.smartyoutubetv2.common.proxy.EmbeddedProxyRoute;
import com.liskovsoft.smartyoutubetv2.common.proxy.PasswdInetSocketAddress;
import com.liskovsoft.smartyoutubetv2.common.proxy.Proxy;
import com.liskovsoft.smartyoutubetv2.common.proxy.ProxyManager;
import com.liskovsoft.smartyoutubetv2.tv.proxy.data.ProxyNodeManager;
import com.liskovsoft.smartyoutubetv2.tv.proxy.data.ProxyPreferences;
import com.liskovsoft.smartyoutubetv2.tv.proxy.data.SubscriptionManager;
import com.liskovsoft.smartyoutubetv2.tv.proxy.model.ProxyNode;
import com.liskovsoft.smartyoutubetv2.tv.proxy.model.SubscriptionProfile;
import java.io.File;
import java.util.HashMap;
import java.util.Map;

/** Main-thread-owned route state. Core readiness is not subscription readiness. */
public final class ProxyRuntimeCoordinator {
    public interface Callback { void onComplete(String error); }
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile boolean busy;
    private static volatile boolean routingReady;
    private static volatile String activeRuntimeId;
    private static volatile String lastError;
    private static volatile long epoch;
    private static long request;
    private ProxyRuntimeCoordinator() { }
    public static boolean isBusy() { return busy || ProxyNodeManager.isSwitching(); }
    public static boolean isRoutingReady(Context context) {
        return isRoutingReady() && new ProxyPreferences(context).isEnabled();
    }
    public static boolean isRoutingReady() {
        return routingReady && EmbeddedProxyRoute.isEnabled()
                && MihomoCoreManager.getState() == MihomoCoreManager.State.RUNNING;
    }
    public static String getActiveRuntimeId() { return activeRuntimeId; }
    public static String getLastError() { return lastError; }
    public static long getEpoch() { return epoch; }

    public static void onCoreReady(Context context) {
        if (new ProxyPreferences(context).isEnabled()) {
            EmbeddedProxyRoute.setEnabled(true);
            setEnabled(context, true, ignored -> { });
        }
    }

    public static void setEnabled(Context context, boolean enabled, Callback callback) {
        Context app = context.getApplicationContext();
        MAIN.post(() -> {
            ProxyPreferences prefs = new ProxyPreferences(app);
            if (!enabled) {
                request++;
                prefs.setEnabled(false);
                routingReady = false;
                lastError = null;
                applyDirect(app);
                callback.onComplete(null);
                return;
            }
            if (isBusy()) { callback.onComplete("正在切换，请完成后重试"); return; }
            SubscriptionProfile active = new SubscriptionManager(app).getActive();
            if (!hasConfig(active)) {
                lastError = "请先添加并更新一个可用订阅";
                routingReady = false;
                callback.onComplete(lastError); return;
            }
            prefs.setEnabled(true);
            routeThroughLocalProxy(app);
            activateInternal(app, active, callback);
        });
    }

    public static void activate(Context context, SubscriptionProfile profile, Callback callback) {
        MAIN.post(() -> activateInternal(context.getApplicationContext(), profile, callback));
    }

    private static void activateInternal(Context app, SubscriptionProfile profile, Callback callback) {
        if (isBusy()) { callback.onComplete("正在切换，请完成后重试"); return; }
        if (!hasConfig(profile)) { callback.onComplete("订阅没有可用的本地配置"); return; }
        if (!new ProxyPreferences(app).isEnabled()) { callback.onComplete("代理已关闭"); return; }
        busy = true;
        boolean wasReady = routingReady;
        routingReady = false;
        lastError = null;
        long ticket = ++request;
        epoch++;
        Map<String, String> selected = new HashMap<>();
        // Never fall back to DIRECT while restoring a missing/renamed saved node.
        selected.put("GLOBAL", "REJECT");
        MihomoCoreManager.recordDiagnosticEvent("开始应用订阅配置");
        MihomoCoreManager.ensureStarted(app, (ignored, startError) -> MAIN.post(() -> {
            if (!valid(app, ticket)) { finish(app, ticket, profile, "操作已取消", false, callback); return; }
            if (startError != null) { finish(app, ticket, profile, startError, wasReady, callback); return; }
            MihomoCoreManager.reloadConfig(new File(profile.configPath), selected, (data, loadError) -> MAIN.post(() -> {
                if (!valid(app, ticket)) { finish(app, ticket, profile, "操作已取消", false, callback); return; }
                if (loadError != null) { finish(app, ticket, profile, loadError, wasReady, callback); return; }
                MihomoCoreManager.recordDiagnosticEvent("订阅配置加载成功，准备恢复节点");
                restoreNode(app, ticket, profile, wasReady, callback);
            }));
        }));
    }

    private static void restoreNode(Context app, long ticket, SubscriptionProfile profile, boolean wasReady, Callback callback) {
        ProxyNodeManager nodes = new ProxyNodeManager(new SubscriptionManager(app));
        nodes.query(profile, (group, list, error) -> MAIN.post(() -> {
            if (!valid(app, ticket)) { finish(app, ticket, profile, "操作已取消", false, callback); return; }
            if (error != null || list.isEmpty()) {
                reject(app, ticket, profile, error != null ? error : "订阅没有可用节点，请更新订阅", wasReady, callback);
                return;
            }
            ProxyNode target = null;
            for (ProxyNode node : list) if (node.runtimeName.equals(profile.selectedNode)) target = node;
            if (target == null) for (ProxyNode node : list) if (node.automatic) { target = node; break; }
            if (target == null) target = list.get(0);
            ProxyNode selected = target;
            MihomoCoreManager.changeProxy(group, selected.runtimeName, (ignored, switchError) -> MAIN.post(() -> {
                if (!valid(app, ticket)) {
                    finish(app, ticket, profile, "操作已取消", false, callback); return;
                }
                if (switchError == null) {
                    new SubscriptionManager(app).saveNode(profile.id, group, selected.runtimeName,
                            selected.automatic ? SubscriptionProfile.MODE_AUTO : SubscriptionProfile.MODE_MANUAL, list.size());
                }
                if (switchError != null) reject(app, ticket, profile, switchError, wasReady, callback);
                else finish(app, ticket, profile, null, false, callback);
            }));
        }));
    }

    private static void reject(Context app, long ticket, SubscriptionProfile profile, String error,
                               boolean wasReady, Callback callback) {
        if (!valid(app, ticket)) {
            finish(app, ticket, profile, "操作已取消", false, callback); return;
        }
        MihomoCoreManager.rejectRuntime(error, (data, result) -> MAIN.post(() ->
                finish(app, ticket, profile, result, wasReady, callback)));
    }

    private static boolean valid(Context app, long ticket) {
        return ticket == request && new ProxyPreferences(app).isEnabled();
    }

    private static void finish(Context app, long ticket, SubscriptionProfile profile, String error,
                               boolean previousReady, Callback callback) {
        if (valid(app, ticket) && new SubscriptionManager(app).get(profile.id) == null) {
            // The phone manager can delete an inactive profile while TV activation
            // is in flight. Never publish a route whose subscription no longer exists.
            MihomoCoreManager.rejectRuntime("订阅已删除，切换已取消", (data, rollbackError) -> MAIN.post(() -> {
                busy = false;
                routingReady = false;
                lastError = ProxyErrors.redact(rollbackError);
                callback.onComplete(lastError);
            }));
            return;
        }
        busy = false;
        if (ticket == request && new SubscriptionManager(app).get(profile.id) == null) {
            new ProxyPreferences(app).setEnabled(false);
            applyDirect(app);
            callback.onComplete("目标订阅已删除，代理已关闭");
            return;
        }
        if (!valid(app, ticket)) { callback.onComplete("操作已取消"); return; }
        lastError = ProxyErrors.redact(error);
        routingReady = error == null || (previousReady && MihomoCoreManager.getState() == MihomoCoreManager.State.RUNNING);
        if (error == null) {
            MihomoCoreManager.acceptRuntime();
            activeRuntimeId = profile.id;
            MihomoCoreManager.recordDiagnosticEvent("节点恢复成功，准备刷新应用网络");
            routeThroughLocalProxy(app);
            // New requests use the selected route. Let current requests finish so
            // a node change does not turn the home page into "Socket closed".
            EmbeddedProxyRoute.refreshAfterNodeChange();
            MihomoCoreManager.recordDiagnosticEvent("代理路由应用成功");
        }
        callback.onComplete(lastError);
    }

    public static void applyDirect(Context context) {
        Context app = context.getApplicationContext();
        routingReady = false;
        epoch++;
        EmbeddedProxyRoute.setEnabled(false);
        ProxyManager manager = new ProxyManager(app);
        manager.saveProxyInfoToPrefs(Proxy.NO_PROXY, false);
        manager.configureSystemProxy();
        GeneralData.instance(app).setProxyEnabled(false);
        OkHttpManager.unhold();
    }

    private static boolean hasConfig(SubscriptionProfile profile) {
        return profile != null && profile.configPath != null && new File(profile.configPath).isFile();
    }

    private static void routeThroughLocalProxy(Context context) {
        EmbeddedProxyRoute.setEnabled(true);
        Proxy proxy = new Proxy(Proxy.Type.HTTP, PasswdInetSocketAddress.createUnresolved(
                MihomoCoreManager.LOOPBACK_HOST, MihomoCoreManager.MIXED_PORT, null, null));
        ProxyManager manager = new ProxyManager(context);
        manager.saveProxyInfoToPrefs(proxy, true);
        manager.configureSystemProxy();
        GeneralData.instance(context).setProxyEnabled(true);
        PlayerTweaksData.instance(context).setPlayerDataSource(PlayerTweaksData.PLAYER_DATA_SOURCE_OKHTTP);
        epoch++;
        OkHttpManager.unhold();
    }
}
