package com.liskovsoft.smartyoutubetv2.tv.proxy.data;

import android.content.Context;

import com.liskovsoft.smartyoutubetv2.tv.proxy.MihomoCoreManager;
import com.liskovsoft.smartyoutubetv2.tv.proxy.ProxyRuntimeCoordinator;
import com.liskovsoft.smartyoutubetv2.tv.proxy.model.SubscriptionProfile;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class SubscriptionManager {
    public interface Callback {
        void onComplete(SubscriptionProfile profile, String error);
    }

    private static final int MAX_CONFIG_BYTES = 10 * 1024 * 1024;
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "smarttube-subscriptions");
        thread.setDaemon(true);
        return thread;
    });

    private final Context context;
    private final ProxyPreferences preferences;
    private final File root;

    public SubscriptionManager(Context context) {
        this.context = context.getApplicationContext();
        preferences = new ProxyPreferences(this.context);
        root = new File(this.context.getFilesDir(), "proxy/subscriptions");
    }

    public synchronized List<SubscriptionProfile> list() {
        return copy(preferences.loadProfiles());
    }

    public synchronized SubscriptionProfile get(String id) {
        if (id == null) {
            return null;
        }
        for (SubscriptionProfile profile : preferences.loadProfiles()) {
            if (id.equals(profile.id)) {
                return profile.copy();
            }
        }
        return null;
    }

    public synchronized SubscriptionProfile getActive() {
        return get(preferences.getActiveId());
    }

    public synchronized SubscriptionProfile add(String name, String url) {
        List<SubscriptionProfile> profiles = preferences.loadProfiles();
        String safeName = normalizeName(name, profiles.size() + 1);
        SubscriptionProfile profile = SubscriptionProfile.create(safeName, url.trim());
        profile.configPath = configFile(profile.id).getAbsolutePath();
        profiles.add(profile);
        String activeId = preferences.getActiveId();
        if (activeId == null) {
            activeId = profile.id;
        }
        preferences.saveProfiles(profiles, activeId);
        return profile.copy();
    }

    public synchronized SubscriptionProfile edit(String id, String name, String url) {
        List<SubscriptionProfile> profiles = preferences.loadProfiles();
        for (SubscriptionProfile profile : profiles) {
            if (profile.id.equals(id)) {
                profile.name = normalizeName(name, profiles.indexOf(profile) + 1);
                profile.url = url.trim();
                preferences.saveProfiles(profiles, preferences.getActiveId());
                return profile.copy();
            }
        }
        return null;
    }

    public void update(String id, Callback callback) {
        IO.execute(() -> updateInternal(id, callback));
    }

    public void activate(String id, Callback callback) {
        SubscriptionProfile profile = get(id);
        if (profile == null || profile.configPath == null || !new File(profile.configPath).isFile()) {
            callback.onComplete(profile, "订阅没有可用的本地配置");
            return;
        }
        if (!preferences.isEnabled()) {
            synchronized (this) {
                preferences.saveProfiles(preferences.loadProfiles(), id);
            }
            ProxyRuntimeCoordinator.applyDirect(context);
            callback.onComplete(get(id), null);
            return;
        }
        ProxyRuntimeCoordinator.activate(context, profile, (error) -> {
            if (error == null) {
                synchronized (this) {
                    List<SubscriptionProfile> profiles = preferences.loadProfiles();
                    preferences.saveProfiles(profiles, id);
                }
            }
            callback.onComplete(get(id), error);
        });
    }

    public synchronized SubscriptionProfile delete(String id) {
        List<SubscriptionProfile> profiles = preferences.loadProfiles();
        boolean wasActive = id != null && id.equals(preferences.getActiveId());
        SubscriptionProfile removed = null;
        for (SubscriptionProfile profile : new ArrayList<>(profiles)) {
            if (profile.id.equals(id)) {
                removed = profile;
                profiles.remove(profile);
                break;
            }
        }
        if (removed == null) {
            return null;
        }
        deleteRecursively(profileDirectory(removed.id));
        String nextActive = wasActive && !profiles.isEmpty() ? profiles.get(0).id
                : wasActive ? null : preferences.getActiveId();
        preferences.saveProfiles(profiles, nextActive);
        if (wasActive) {
            if (nextActive == null || !preferences.isEnabled()) {
                ProxyRuntimeCoordinator.applyDirect(context);
            } else {
                SubscriptionProfile next = get(nextActive);
                if (next != null && next.configPath != null && new File(next.configPath).isFile()) {
                    ProxyRuntimeCoordinator.activate(context, next, ignored -> { });
                } else {
                    ProxyRuntimeCoordinator.applyDirect(context);
                }
            }
        }
        return removed.copy();
    }

    public synchronized void saveNode(String id, String group, String node, String mode,
                                      int nodeCount) {
        List<SubscriptionProfile> profiles = preferences.loadProfiles();
        for (SubscriptionProfile profile : profiles) {
            if (profile.id.equals(id)) {
                profile.selectedGroup = group;
                profile.selectedNode = node;
                profile.selectedNodeMode = mode;
                profile.nodeCount = nodeCount;
                break;
            }
        }
        preferences.saveProfiles(profiles, preferences.getActiveId());
    }

    private void updateInternal(String id, Callback callback) {
        SubscriptionProfile profile = get(id);
        if (profile == null) {
            callback.onComplete(null, "订阅不存在");
            return;
        }
        File directory = profileDirectory(profile.id);
        File temporary = new File(directory, "config.tmp");
        File config = new File(directory, "config.yaml");
        try {
            ensureDirectory(directory);
            download(profile.url, temporary);
        } catch (Exception error) {
            temporary.delete();
            markUpdate(profile.id, false);
            callback.onComplete(get(profile.id), safeError(error));
            return;
        }

        MihomoCoreManager.validateConfig(temporary, (ignored, validationError) -> IO.execute(() -> {
            if (validationError != null) {
                temporary.delete();
                markUpdate(profile.id, false);
                callback.onComplete(get(profile.id), "订阅配置验证失败");
                return;
            }
            try {
                atomicReplace(temporary, config);
                markUpdate(profile.id, true);
                SubscriptionProfile updated = get(profile.id);
                if (profile.id.equals(preferences.getActiveId())) {
                    activate(profile.id, callback);
                } else {
                    callback.onComplete(updated, null);
                }
            } catch (IOException error) {
                temporary.delete();
                markUpdate(profile.id, false);
                callback.onComplete(get(profile.id), "无法保存订阅配置");
            }
        }));
    }

    private synchronized void markUpdate(String id, boolean success) {
        List<SubscriptionProfile> profiles = preferences.loadProfiles();
        for (SubscriptionProfile profile : profiles) {
            if (profile.id.equals(id)) {
                profile.lastUpdateTime = System.currentTimeMillis();
                profile.lastUpdateSuccess = success;
                profile.configPath = configFile(profile.id).getAbsolutePath();
                break;
            }
        }
        preferences.saveProfiles(profiles, preferences.getActiveId());
    }

    private File configFile(String id) {
        return new File(profileDirectory(id), "config.yaml");
    }

    private File profileDirectory(String id) {
        return new File(root, "sub_" + id);
    }

    private static void download(String value, File destination) throws IOException {
        URL url = new URL(value);
        if (!isHttpProtocol(url)) {
            throw new IOException("订阅地址必须使用 HTTP 或 HTTPS");
        }
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(20_000);
        connection.setInstanceFollowRedirects(true);
        // Many subscription panels select the output format from the client identifier.
        // Use the Mihomo-compatible identifier so the response is Clash YAML.
        connection.setRequestProperty("User-Agent", "clash.meta");
        connection.setRequestProperty("Accept", "application/yaml, text/yaml, text/plain, */*");
        try {
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IOException("订阅服务器返回 " + status);
            }
            if (!isHttpProtocol(connection.getURL())) {
                throw new IOException("订阅重定向仅支持 HTTP 或 HTTPS");
            }
            int total = 0;
            try (BufferedInputStream input = new BufferedInputStream(connection.getInputStream());
                 FileOutputStream output = new FileOutputStream(destination)) {
                byte[] buffer = new byte[32 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    total += count;
                    if (total > MAX_CONFIG_BYTES) {
                        throw new IOException("订阅配置超过 10 MB");
                    }
                    output.write(buffer, 0, count);
                }
                output.flush();
                output.getFD().sync();
            }
            if (total == 0) {
                throw new IOException("订阅内容为空");
            }
        } finally {
            connection.disconnect();
        }
    }

    private static boolean isHttpProtocol(URL url) {
        String protocol = url.getProtocol();
        return "http".equalsIgnoreCase(protocol) || "https".equalsIgnoreCase(protocol);
    }

    private static void atomicReplace(File temporary, File config) throws IOException {
        File backup = new File(config.getParentFile(), "config.previous");
        if (backup.exists() && !backup.delete()) {
            throw new IOException("无法清理旧备份");
        }
        if (config.exists() && !config.renameTo(backup)) {
            throw new IOException("无法备份有效配置");
        }
        if (!temporary.renameTo(config)) {
            if (backup.exists()) {
                backup.renameTo(config);
            }
            throw new IOException("无法替换订阅配置");
        }
        backup.delete();
    }

    private static void ensureDirectory(File directory) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("无法创建订阅目录");
        }
    }

    private static String normalizeName(String name, int index) {
        String trimmed = name == null ? "" : name.trim();
        return trimmed.isEmpty() ? "订阅 " + index : trimmed;
    }

    private static String safeError(Exception error) {
        String message = error.getMessage();
        return message == null || message.isEmpty() ? "订阅更新失败" : message;
    }

    private static List<SubscriptionProfile> copy(List<SubscriptionProfile> source) {
        List<SubscriptionProfile> result = new ArrayList<>();
        for (SubscriptionProfile profile : source) {
            result.add(profile.copy());
        }
        return result;
    }

    private static void deleteRecursively(File target) {
        File[] children = target.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        target.delete();
    }
}
