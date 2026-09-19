package com.liskovsoft.smartyoutubetv2.tv.proxy;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import io.github.oviron.libmihomo.Clash;
import io.github.oviron.libmihomo.InvokeInterface;

import com.liskovsoft.smartyoutubetv2.tv.proxy.data.ProxyPreferences;

/** Embedded Mihomo lifecycle and action bridge for the stproxy flavor. */
public final class MihomoCoreManager {
    public enum State { STOPPED, STARTING, RUNNING, FAILED }

    public interface ResultCallback {
        void onResult(String data, String error);
    }

    public static final String LOOPBACK_HOST = "127.0.0.1";
    public static final int MIXED_PORT = 7890;

    private static final String TAG = "MihomoCoreManager";
    private static final String HOME_DIRECTORY = "mihomo";
    private static final String CONFIG_FILE = "config.yaml";
    private static final String DIAGNOSTIC_PREFS = "mihomo_startup_diagnostics";
    private static final String KEY_DIAGNOSTIC_STAGE = "stage";
    private static final String KEY_DIAGNOSTIC_TIME = "time";
    private static final int READY_TIMEOUT_MS = 8_000;
    private static final int CONNECT_TIMEOUT_MS = 200;
    private static final int RETRY_DELAY_MS = 100;

    private static final String PHASE_TWO_CONFIG =
            "mixed-port: 7890\n" +
            "allow-lan: false\n" +
            "bind-address: 127.0.0.1\n" +
            "mode: global\n" +
            "log-level: info\n" +
            "ipv6: false\n" +
            "tun:\n" +
            "  enable: false\n" +
            "proxies: []\n" +
            "proxy-groups: []\n" +
            "rules:\n" +
            "  - MATCH,DIRECT\n";

    private static final AtomicBoolean START_REQUESTED = new AtomicBoolean(false);
    private static final AtomicReference<State> STATE = new AtomicReference<>(State.STOPPED);
    private static final AtomicLong ACTION_IDS = new AtomicLong();
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "smarttube-mihomo");
        thread.setDaemon(true);
        return thread;
    });

    private static volatile String lastError;
    private static volatile Context appContext;

    private MihomoCoreManager() {
    }

    /** Starts automatically only when the user previously enabled the proxy. */
    public static void startIfEnabled(Context context) {
        Context application = context.getApplicationContext();
        if (new ProxyPreferences(application).isEnabled()) {
            start(application);
        }
    }

    public static void start(Context context) {
        appContext = context.getApplicationContext();
        if (!START_REQUESTED.compareAndSet(false, true)) {
            return;
        }
        STATE.set(State.STARTING);
        recordStage(appContext, "开始初始化");
        EXECUTOR.execute(() -> startInternal(appContext));
    }

    public static String getStartupDiagnostic(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(DIAGNOSTIC_PREFS, Context.MODE_PRIVATE)
                .getString(KEY_DIAGNOSTIC_STAGE, "尚未测试");
    }

    public static State getState() {
        return STATE.get();
    }

    public static String getLastError() {
        return lastError;
    }

    public static void validateConfig(File file, ResultCallback callback) {
        invoke("validateConfig", file.getAbsolutePath(), (data, error) -> {
            String validationError = error != null ? error : emptyToNull(data);
            callback.onResult("", validationError);
        });
    }

    public static void reloadConfig(File source, Map<String, String> selectedMap,
                                    ResultCallback callback) {
        EXECUTOR.execute(() -> prepareRuntimeConfig(source, selectedMap, callback));
    }

    public static void getProxies(ResultCallback callback) {
        invoke("getProxies", "", callback);
    }

    public static void changeProxy(String group, String proxy, ResultCallback callback) {
        try {
            JSONObject params = new JSONObject();
            params.put("group-name", group);
            params.put("proxy-name", proxy);
            invoke("changeProxy", params.toString(), callback);
        } catch (JSONException error) {
            callback.onResult(null, error.getMessage());
        }
    }

    public static void testDelay(String proxy, String url, int timeoutMs,
                                 ResultCallback callback) {
        try {
            JSONObject params = new JSONObject();
            params.put("proxy-name", proxy);
            params.put("test-url", url);
            params.put("timeout", timeoutMs);
            invoke("testDelay", params.toString(), callback);
        } catch (JSONException error) {
            callback.onResult(null, error.getMessage());
        }
    }

    public static void reconnect(ResultCallback callback) {
        reloadConfig(runtimeConfig(), Collections.emptyMap(), callback);
    }

    private static void startInternal(Context context) {
        try {
            recordStage(context, "正在准备初始化配置");
            File home = new File(context.getFilesDir(), HOME_DIRECTORY);
            ensureDirectory(home);
            File config = new File(home, CONFIG_FILE);
            writeBootstrapConfigIfMissing(config);

            recordStage(context, "正在加载 Mihomo native 库");
            Clash.INSTANCE.load(context.getApplicationInfo().nativeLibraryDir);
            if (!Clash.INSTANCE.isLoaded()) {
                throw new IllegalStateException("libmihomo native libraries failed to load");
            }
            recordStage(context, "Mihomo native 库加载成功");

            String initParams = "{\"home-dir\":\"" + jsonEscape(home.getAbsolutePath()) +
                    "\",\"version\":" + Build.VERSION.SDK_INT + "}";
            recordStage(context, "正在执行 Mihomo quickSetup");
            Clash.INSTANCE.quickSetup(initParams, "{}", result -> {
                recordStage(context, "Mihomo quickSetup 已返回");
                if (result != null && !result.isEmpty()) {
                    fail("Mihomo setup failed: " + result, null);
                    return;
                }
                recordStage(context, "正在配置本地代理端口");
                enforceLocalRuntime((ignored, error) -> {
                    if (error != null) {
                        fail("Unable to enforce local Mihomo runtime", null);
                        return;
                    }
                    recordStage(context, "正在等待 127.0.0.1:7890");
                    EXECUTOR.execute(() -> {
                        if (awaitLoopbackProxy()) {
                            ProxyRuntimeCoordinator.onCoreReady(context);
                        }
                    });
                });
            });
        } catch (Throwable error) {
            fail("Mihomo initialization failed", error);
        }
    }

    private static void prepareRuntimeConfig(File source, Map<String, String> selectedMap,
                                             ResultCallback callback) {
        if (STATE.get() != State.RUNNING) {
            callback.onResult(null, "Mihomo core is not ready");
            return;
        }
        File target = runtimeConfig();
        if (source == null || !source.isFile()) {
            callback.onResult(null, "Subscription config is missing");
            return;
        }
        if (source.equals(target)) {
            applyRuntimeConfig(selectedMap, callback);
            return;
        }

        File temporary = new File(target.getParentFile(), CONFIG_FILE + ".tmp");
        File previous = new File(target.getParentFile(), CONFIG_FILE + ".previous");
        try {
            copyFile(source, temporary);
        } catch (IOException error) {
            callback.onResult(null, "Unable to stage config: " + error.getMessage());
            return;
        }

        validateConfig(temporary, (ignored, validationError) -> EXECUTOR.execute(() -> {
            if (validationError != null) {
                temporary.delete();
                callback.onResult(null, validationError);
                return;
            }
            try {
                if (target.isFile()) {
                    copyFile(target, previous);
                }
                replaceFile(temporary, target);
            } catch (IOException error) {
                callback.onResult(null, "Unable to activate config: " + error.getMessage());
                return;
            }
            applyRuntimeConfig(selectedMap, (data, error) -> {
                if (error == null) {
                    previous.delete();
                    callback.onResult(data, null);
                } else {
                    EXECUTOR.execute(() -> rollback(previous, target, callback, error));
                }
            });
        }));
    }

    private static void applyRuntimeConfig(Map<String, String> selectedMap,
                                           ResultCallback callback) {
        try {
            JSONObject setup = new JSONObject();
            setup.put("selected-map", new JSONObject(selectedMap));
            invoke("setupConfig", setup.toString(), (data, error) -> {
                String setupError = error != null ? error : emptyToNull(data);
                if (setupError != null) {
                    callback.onResult(null, setupError);
                    return;
                }
                enforceLocalRuntime(
                        (updateData, updateError) -> EXECUTOR.execute(() -> {
                            String finalError = updateError != null ? updateError : emptyToNull(updateData);
                            if (finalError == null && awaitLoopbackProxy()) {
                                callback.onResult("", null);
                            } else {
                                callback.onResult(null, finalError != null ? finalError : lastError);
                            }
                        }));
            });
        } catch (JSONException error) {
            callback.onResult(null, error.getMessage());
        }
    }

    private static void enforceLocalRuntime(ResultCallback callback) {
        invoke("updateConfig",
                "{\"mixed-port\":7890,\"allow-lan\":false," +
                        "\"mode\":\"global\",\"tun\":{\"enable\":false}}",
                callback);
    }

    private static void rollback(File previous, File target, ResultCallback callback,
                                 String originalError) {
        if (!previous.isFile()) {
            callback.onResult(null, originalError);
            return;
        }
        try {
            copyFile(previous, target);
            previous.delete();
            applyRuntimeConfig(Collections.emptyMap(), (ignored, rollbackError) ->
                    callback.onResult(null, originalError));
        } catch (IOException ignored) {
            callback.onResult(null, originalError);
        }
    }

    private static void invoke(String method, String data, ResultCallback callback) {
        if (!Clash.INSTANCE.isLoaded()) {
            callback.onResult(null, "Mihomo JNI bridge is not loaded");
            return;
        }
        try {
            JSONObject action = new JSONObject();
            action.put("id", Long.toString(ACTION_IDS.incrementAndGet()));
            action.put("method", method);
            action.put("data", data == null ? "" : data);
            Clash.INSTANCE.invokeAction(action.toString(), new InvokeInterface() {
                @Override
                public void onResult(String result) {
                    parseActionResult(result, callback);
                }
            });
        } catch (Throwable error) {
            callback.onResult(null, error.getMessage());
        }
    }

    private static void parseActionResult(String result, ResultCallback callback) {
        try {
            JSONObject json = new JSONObject(result == null ? "" : result);
            Object value = json.opt("data");
            String data = value == null || value == JSONObject.NULL ? "" : String.valueOf(value);
            if (json.optInt("code", -1) == 0) {
                callback.onResult(data, null);
            } else {
                callback.onResult(null, data.isEmpty() ? "Mihomo action failed" : data);
            }
        } catch (JSONException error) {
            callback.onResult(null, "Invalid Mihomo response");
        }
    }

    private static boolean awaitLoopbackProxy() {
        long deadline = System.currentTimeMillis() + READY_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(LOOPBACK_HOST, MIXED_PORT), CONNECT_TIMEOUT_MS);
                lastError = null;
                STATE.set(State.RUNNING);
                recordStage(appContext, "初始化成功：127.0.0.1:7890 已就绪");
                Log.i(TAG, "Mihomo is ready on " + LOOPBACK_HOST + ":" + MIXED_PORT);
                return true;
            } catch (IOException ignored) {
                try {
                    Thread.sleep(RETRY_DELAY_MS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    fail("Interrupted while waiting for Mihomo", interrupted);
                    return false;
                }
            }
        }
        fail("Mihomo did not open the loopback proxy before timeout", null);
        return false;
    }

    private static File runtimeConfig() {
        if (appContext == null) {
            throw new IllegalStateException("Mihomo context is not initialized");
        }
        return new File(new File(appContext.getFilesDir(), HOME_DIRECTORY), CONFIG_FILE);
    }

    private static void ensureDirectory(File directory) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Unable to create Mihomo home directory");
        }
    }

    private static void writeBootstrapConfigIfMissing(File config) throws IOException {
        if (config.isFile()) {
            return;
        }
        File temporary = new File(config.getParentFile(), CONFIG_FILE + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            output.write(PHASE_TWO_CONFIG.getBytes(StandardCharsets.UTF_8));
            output.flush();
            output.getFD().sync();
        }
        replaceFile(temporary, config);
    }

    private static void copyFile(File source, File target) throws IOException {
        try (FileInputStream input = new FileInputStream(source);
             FileOutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[32 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            output.flush();
            output.getFD().sync();
        }
    }

    private static void replaceFile(File source, File target) throws IOException {
        if (target.exists() && !target.delete()) {
            throw new IOException("Unable to replace old config");
        }
        if (!source.renameTo(target)) {
            copyFile(source, target);
            if (!source.delete()) {
                Log.w(TAG, "Unable to remove staged config");
            }
        }
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private static void recordStage(Context context, String stage) {
        if (context == null) {
            return;
        }
        context.getApplicationContext()
                .getSharedPreferences(DIAGNOSTIC_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_DIAGNOSTIC_STAGE, stage)
                .putLong(KEY_DIAGNOSTIC_TIME, System.currentTimeMillis())
                .commit();
    }

    private static void fail(String message, Throwable error) {
        lastError = error == null || error.getMessage() == null
                ? message : message + ": " + error.getMessage();
        STATE.set(State.FAILED);
        recordStage(appContext, "初始化失败：" + lastError);
        if (error == null) {
            Log.e(TAG, message);
        } else {
            Log.e(TAG, message, error);
        }
    }
}
