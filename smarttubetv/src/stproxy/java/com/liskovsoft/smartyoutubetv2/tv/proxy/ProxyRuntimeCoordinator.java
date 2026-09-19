package com.liskovsoft.smartyoutubetv2.tv.proxy;

import android.content.Context;

import com.liskovsoft.sharedutils.okhttp.OkHttpManager;
import com.liskovsoft.smartyoutubetv2.common.prefs.GeneralData;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerTweaksData;
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
import java.util.List;
import java.util.Map;

public final class ProxyRuntimeCoordinator {
    public interface Callback {
        void onComplete(String error);
    }

    private ProxyRuntimeCoordinator() {
    }

    public static void onCoreReady(Context context) {
        Context app = context.getApplicationContext();
        ProxyPreferences preferences = new ProxyPreferences(app);
        if (!preferences.isEnabled()) {
            applyDirect(app);
            return;
        }
        SubscriptionProfile active = new SubscriptionManager(app).getActive();
        if (active == null || active.configPath == null || !new File(active.configPath).isFile()) {
            applyDirect(app);
            return;
        }
        activate(app, active, ignored -> { });
    }

    public static void setEnabled(Context context, boolean enabled, Callback callback) {
        Context app = context.getApplicationContext();
        ProxyPreferences preferences = new ProxyPreferences(app);
        if (!enabled) {
            preferences.setEnabled(false);
            applyDirect(app);
            callback.onComplete(null);
            return;
        }
        SubscriptionProfile active = new SubscriptionManager(app).getActive();
        if (active == null || active.configPath == null || !new File(active.configPath).isFile()) {
            preferences.setEnabled(false);
            applyDirect(app);
            callback.onComplete("请先添加并更新一个可用订阅");
            return;
        }
        preferences.setEnabled(true);
        activate(app, active, callback);
    }

    public static void activate(Context context, SubscriptionProfile profile, Callback callback) {
        Context app = context.getApplicationContext();
        Map<String, String> selected = new HashMap<>();
        if (profile.selectedGroup != null && profile.selectedNode != null) {
            selected.put(profile.selectedGroup, profile.selectedNode);
        }
        MihomoCoreManager.reloadConfig(new File(profile.configPath), selected, (ignored, error) -> {
            if (error != null) {
                callback.onComplete(error);
                return;
            }
            restoreNodeAndRoute(app, profile, callback);
        });
    }

    public static void applyDirect(Context context) {
        Context app = context.getApplicationContext();
        ProxyManager manager = new ProxyManager(app);
        manager.saveProxyInfoToPrefs(Proxy.NO_PROXY, false);
        manager.configureSystemProxy();
        GeneralData.instance(app).setProxyEnabled(false);
        OkHttpManager.unhold();
    }

    private static void restoreNodeAndRoute(Context context, SubscriptionProfile profile,
                                            Callback callback) {
        SubscriptionManager subscriptions = new SubscriptionManager(context);
        ProxyNodeManager nodes = new ProxyNodeManager(subscriptions);
        nodes.query(profile, (group, list, queryError) -> {
            if (queryError != null) {
                callback.onComplete(queryError);
                return;
            }
            ProxyNode target = findSaved(profile, list);
            if (target == null && !list.isEmpty()) {
                target = list.get(0);
            }
            if (target == null || group == null) {
                routeThroughLocalProxy(context);
                callback.onComplete(null);
                return;
            }
            ProxyNode selected = target;
            nodes.switchNode(profile, group, selected, list.size(), switchError -> {
                if (switchError == null) {
                    routeThroughLocalProxy(context);
                }
                callback.onComplete(switchError);
            });
        });
    }

    private static ProxyNode findSaved(SubscriptionProfile profile, List<ProxyNode> nodes) {
        if (profile.selectedNode == null) {
            if (SubscriptionProfile.MODE_AUTO.equals(profile.selectedNodeMode)) {
                for (ProxyNode node : nodes) {
                    if (node.automatic) {
                        return node;
                    }
                }
            }
            return null;
        }
        for (ProxyNode node : nodes) {
            if (profile.selectedNode.equals(node.runtimeName)) {
                return node;
            }
        }
        if (SubscriptionProfile.MODE_AUTO.equals(profile.selectedNodeMode)) {
            for (ProxyNode node : nodes) {
                if (node.automatic) {
                    return node;
                }
            }
        }
        return null;
    }

    private static void routeThroughLocalProxy(Context context) {
        Proxy proxy = new Proxy(Proxy.Type.HTTP,
                PasswdInetSocketAddress.createUnresolved(
                        MihomoCoreManager.LOOPBACK_HOST, MihomoCoreManager.MIXED_PORT,
                        null, null));
        ProxyManager manager = new ProxyManager(context);
        manager.saveProxyInfoToPrefs(proxy, true);
        manager.configureSystemProxy();
        GeneralData.instance(context).setProxyEnabled(true);
        PlayerTweaksData.instance(context).setPlayerDataSource(PlayerTweaksData.PLAYER_DATA_SOURCE_OKHTTP);
        OkHttpManager.unhold();
    }
}
