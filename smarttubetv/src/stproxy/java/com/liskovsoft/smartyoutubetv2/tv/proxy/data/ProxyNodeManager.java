package com.liskovsoft.smartyoutubetv2.tv.proxy.data;

import com.liskovsoft.smartyoutubetv2.tv.proxy.MihomoCoreManager;
import com.liskovsoft.smartyoutubetv2.tv.proxy.ProxyErrors;
import com.liskovsoft.smartyoutubetv2.tv.proxy.ProxyRuntimeCoordinator;
import com.liskovsoft.smartyoutubetv2.common.proxy.EmbeddedProxyRoute;
import android.os.Handler;
import android.os.Looper;
import com.liskovsoft.smartyoutubetv2.tv.proxy.model.ProxyNode;
import com.liskovsoft.smartyoutubetv2.tv.proxy.model.SubscriptionProfile;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ProxyNodeManager {
    public interface NodesCallback {
        void onResult(String group, List<ProxyNode> nodes, String error);
    }

    public interface SwitchCallback {
        void onResult(String error);
    }

    public interface DelayProgress {
        void onProgress(int completed, int total, ProxyNode node);
        void onComplete();
    }

    private static final String TEST_URL = "https://www.gstatic.com/generate_204";
    private static final int TEST_TIMEOUT_MS = 5_000;
    private static final long CACHE_MS = 5 * 60 * 1_000L;
    private static final Set<String> INTERNAL = new HashSet<>();
    private static final Map<String, DelayEntry> DELAYS = new ConcurrentHashMap<>();
    private static final AtomicBoolean SWITCHING = new AtomicBoolean();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    public static boolean isSwitching() { return SWITCHING.get(); }
    public static final class TestSession {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        public void cancel() { cancelled.set(true); }
        public boolean isCancelled() { return cancelled.get(); }
    }
    private static final ExecutorService DELAY_EXECUTOR = Executors.newFixedThreadPool(5, runnable -> {
        Thread thread = new Thread(runnable, "smarttube-proxy-delay");
        thread.setDaemon(true);
        return thread;
    });

    static {
        Collections.addAll(INTERNAL, "DIRECT", "REJECT", "REJECT-DROP", "PASS", "COMPATIBLE");
    }

    private final SubscriptionManager subscriptions;

    public ProxyNodeManager(SubscriptionManager subscriptions) {
        this.subscriptions = subscriptions;
    }

    public void query(SubscriptionProfile profile, NodesCallback callback) {
        MihomoCoreManager.getProxies((data, error) -> {
            if (error != null) {
                callback.onResult(null, Collections.emptyList(), error);
                return;
            }
            try {
                Parsed parsed = parse(data, profile);
                callback.onResult(parsed.group, parsed.nodes, null);
            } catch (JSONException parseError) {
                callback.onResult(null, Collections.emptyList(), "无法读取 Mihomo 节点");
            }
        });
    }

    public void switchNode(SubscriptionProfile profile, String group, ProxyNode node,
                           int nodeCount, SwitchCallback callback) {
        MAIN.post(() -> {
        SubscriptionProfile active = subscriptions.getActive();
        if (active == null || !active.id.equals(profile.id)
                || !profile.id.equals(ProxyRuntimeCoordinator.getActiveRuntimeId())) {
            callback.onResult("当前订阅已变化，请刷新节点列表"); return;
        }
        if (!EmbeddedProxyRoute.isEnabled() || !ProxyRuntimeCoordinator.isRoutingReady()
                || ProxyRuntimeCoordinator.isBusy()
                || !SWITCHING.compareAndSet(false, true)) {
            callback.onResult("请先连接代理，并等待当前操作完成"); return;
        }
        long epoch = ProxyRuntimeCoordinator.getEpoch();
        MihomoCoreManager.recordDiagnosticEvent(
                "开始切换节点：" + ProxyErrors.redact(node.runtimeName));
        MihomoCoreManager.changeProxy(group, node.runtimeName, (ignored, error) -> MAIN.post(() -> {
            SWITCHING.set(false);
            if (epoch != ProxyRuntimeCoordinator.getEpoch() || !EmbeddedProxyRoute.isEnabled()) {
                callback.onResult("操作已取消，代理状态已经变化"); return;
            }
            if (error == null) {
                node.selected = true;
                subscriptions.saveNode(profile.id, group, node.runtimeName,
                        node.automatic ? SubscriptionProfile.MODE_AUTO : SubscriptionProfile.MODE_MANUAL,
                        nodeCount);
                EmbeddedProxyRoute.refresh();
                MihomoCoreManager.recordDiagnosticEvent("节点切换成功");
            }
            callback.onResult(error);
        }));
        });
    }

    public TestSession testAll(SubscriptionProfile profile, List<ProxyNode> nodes,
                        DelayProgress progress) {
        TestSession session = new TestSession();
        long epoch = ProxyRuntimeCoordinator.getEpoch();
        List<ProxyNode> targets = new ArrayList<>();
        for (ProxyNode node : nodes) {
            node.delayMs = ProxyNode.DELAY_TESTING;
            targets.add(node);
        }
        if (targets.isEmpty()) {
            progress.onComplete();
            return session;
        }
        AtomicInteger completed = new AtomicInteger();
        AtomicInteger next = new AtomicInteger();
        Runnable[] launchNext = new Runnable[1];
        launchNext[0] = () -> {
            if (session.isCancelled()) return;
            if (epoch != ProxyRuntimeCoordinator.getEpoch()) {
                session.cancel();
                progress.onComplete();
                return;
            }
            int index = next.getAndIncrement();
            if (index >= targets.size()) {
                return;
            }
            ProxyNode node = targets.get(index);
            MihomoCoreManager.testDelay(
                    node.runtimeName, TEST_URL, TEST_TIMEOUT_MS, (data, error) -> {
                        if (session.isCancelled()) return;
                        if (epoch != ProxyRuntimeCoordinator.getEpoch()) {
                            session.cancel(); progress.onComplete(); return;
                        }
                        int delay = ProxyNode.DELAY_TIMEOUT;
                        if (error == null) {
                            try {
                                int parsed = Integer.parseInt(data);
                                if (parsed > 0) {
                                    delay = parsed;
                                }
                            } catch (NumberFormatException ignored) {
                            }
                        }
                        node.delayMs = delay;
                        DELAYS.put(cacheKey(profile.id, node.runtimeName),
                                new DelayEntry(delay, System.currentTimeMillis()));
                        int count = completed.incrementAndGet();
                        progress.onProgress(count, targets.size(), node);
                        if (count == targets.size()) {
                            progress.onComplete();
                        } else {
                            DELAY_EXECUTOR.execute(launchNext[0]);
                        }
                    });
        };
        int concurrency = Math.min(5, targets.size());
        for (int index = 0; index < concurrency; index++) {
            DELAY_EXECUTOR.execute(launchNext[0]);
        }
        return session;
    }

    private static Parsed parse(String data, SubscriptionProfile profile) throws JSONException {
        JSONObject proxies = new JSONObject(data).getJSONObject("proxies");
        String group = selectGroup(proxies, profile.selectedGroup);
        if (group == null) {
            return new Parsed(null, Collections.emptyList());
        }
        JSONObject groupObject = proxies.getJSONObject(group);
        JSONArray members = groupObject.optJSONArray("all");
        String current = groupObject.optString("now", "");
        List<ProxyNode> nodes = new ArrayList<>();
        if (members != null) {
            for (int index = 0; index < members.length(); index++) {
                String name = members.optString(index, "");
                if (name.isEmpty() || INTERNAL.contains(name.toUpperCase(Locale.US))) {
                    continue;
                }
                JSONObject object = proxies.optJSONObject(name);
                // A selector/automatic group containing DIRECT can bypass the proxy even
                // when GLOBAL points at that group. Only offer complete proxy-only paths.
                if (!isProxyOnly(proxies, name, new HashSet<>())) continue;
                String type = object.optString("type", "Unknown");
                boolean auto = isAutomatic(type);
                ProxyNode node = new ProxyNode(auto ? "自动 · " + name
                        : isNestedSelector(type) ? "策略组 · " + name : name, name, type, auto);
                node.selected = name.equals(current);
                DelayEntry cached = DELAYS.get(cacheKey(profile.id, name));
                if (cached != null && System.currentTimeMillis() - cached.time < CACHE_MS) {
                    node.delayMs = cached.delay;
                }
                nodes.add(node);
            }
        }
        Collections.sort(nodes, (left, right) ->
                String.CASE_INSENSITIVE_ORDER.compare(left.name, right.name));
        return new Parsed(group, nodes);
    }

    private static boolean isProxyOnly(JSONObject proxies, String name, Set<String> visiting) {
        if (INTERNAL.contains(name.toUpperCase(Locale.US)) || !visiting.add(name)) return false;
        JSONObject object = proxies.optJSONObject(name);
        if (object == null) { visiting.remove(name); return false; }
        String type = object.optString("type", "");
        if ("direct".equalsIgnoreCase(type) || "pass".equalsIgnoreCase(type)
                || "reject".equalsIgnoreCase(type)) { visiting.remove(name); return false; }
        JSONArray members = object.optJSONArray("all");
        boolean safe = members == null || members.length() > 0;
        if (members != null) {
            for (int index = 0; safe && index < members.length(); index++) {
                safe = isProxyOnly(proxies, members.optString(index, ""), visiting);
            }
        }
        visiting.remove(name);
        return safe;
    }

    private static String selectGroup(JSONObject proxies, String savedGroup) {
        // Runtime is deliberately global; editing a saved subscription group can have no effect.
        if (isSelectable(proxies.optJSONObject("GLOBAL"))) {
            return "GLOBAL";
        }
        return null;
    }

    private static boolean isSelectable(JSONObject object) {
        return object != null && object.optJSONArray("all") != null;
    }

    private static boolean isAutomatic(String type) {
        String lower = type.toLowerCase(Locale.US);
        return lower.contains("urltest") || lower.contains("url-test") ||
                lower.contains("fallback") || lower.contains("loadbalance") ||
                lower.contains("load-balance");
    }

    private static boolean isNestedSelector(String type) {
        return type.toLowerCase(Locale.US).contains("selector");
    }

    private static String cacheKey(String subscriptionId, String node) {
        return subscriptionId + '\n' + node;
    }

    private static final class Parsed {
        final String group;
        final List<ProxyNode> nodes;

        Parsed(String group, List<ProxyNode> nodes) {
            this.group = group;
            this.nodes = nodes;
        }
    }

    private static final class DelayEntry {
        final int delay;
        final long time;

        DelayEntry(int delay, long time) {
            this.delay = delay;
            this.time = time;
        }
    }
}
