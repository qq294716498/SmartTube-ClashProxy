package com.liskovsoft.smartyoutubetv2.tv.proxy;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;
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
    private static final String DIAGNOSTIC_LOG_FILE = "mihomo-initialization.log";
    private static final int READY_TIMEOUT_MS = 8_000;
    private static final int CONNECT_TIMEOUT_MS = 200;
    private static final int RETRY_DELAY_MS = 100;

    private static final String PHASE_TWO_CONFIG =
            "mixed-port: 7890\n" +
            "allow-lan: false\n" +
            "bind-address: 127.0.0.1\n" +
            "mode: rule\n" +
            "log-level: info\n" +
            "ipv6: false\n" +
            "tun:\n" +
            "  enable: false\n" +
            "proxies: []\n" +
            "proxy-groups: []\n" +
            "rules:\n" +
            "  - MATCH,REJECT\n";

    private static final AtomicBoolean START_REQUESTED = new AtomicBoolean(false);
    private static final AtomicReference<State> STATE = new AtomicReference<>(State.STOPPED);
    private static final AtomicLong ACTION_IDS = new AtomicLong();
    private static final AtomicBoolean RELOADING = new AtomicBoolean();
    private static volatile Map<String, String> rollbackSelection = Collections.singletonMap("GLOBAL", "REJECT");
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "smarttube-mihomo");
        thread.setDaemon(true);
        return thread;
    });

    private static volatile String lastError;
    private static volatile Context appContext;
    private static volatile boolean crashHandlerInstalled;

    private MihomoCoreManager() {
    }

    /** Starts automatically only when the user previously enabled the proxy. */
    public static void startIfEnabled(Context context) {
        Context application = context.getApplicationContext();
        if (new ProxyPreferences(application).isEnabled()) {
            ProxyRuntimeCoordinator.onCoreReady(application);
        }
    }

    public static void start(Context context) {
        appContext = context.getApplicationContext();
        if (!START_REQUESTED.compareAndSet(false, true)) {
            return;
        }
        STATE.set(State.STARTING);
        recordStage(appContext, "开始初始化");
        Runnable initializer = () -> startInternal(appContext);
        if (Looper.myLooper() == Looper.getMainLooper()) {
            initializer.run();
        } else {
            new Handler(Looper.getMainLooper()).post(initializer);
        }
    }

    public static String getStartupDiagnostic(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(DIAGNOSTIC_PREFS, Context.MODE_PRIVATE)
                .getString(KEY_DIAGNOSTIC_STAGE, "尚未测试");
    }

    public static synchronized void installDiagnosticCrashHandler(Context context) {
        if (crashHandlerInstalled) {
            return;
        }
        Context application = context.getApplicationContext();
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            appendDiagnostic(application, "未捕获异常，线程=" + thread.getName());
            appendDiagnostic(application, stackTrace(error));
            if (previous != null) {
                previous.uncaughtException(thread, error);
            }
        });
        crashHandlerInstalled = true;
    }

    public static String exportDiagnosticLog(Context context) {
        Context application = context.getApplicationContext();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return "当前系统不支持免权限导出";
        }

        String timestamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        String fileName = "mihomo-diagnostics-" + timestamp + ".txt";
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/SmartTube-Proxy");
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);

        Uri uri = application.getContentResolver().insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) {
            return "导出失败：无法创建下载文件";
        }

        try (OutputStream output = application.getContentResolver().openOutputStream(uri)) {
            if (output == null) {
                throw new IOException("Unable to open output stream");
            }
            output.write(buildDiagnosticReport(application).getBytes(StandardCharsets.UTF_8));
            output.flush();
            values.clear();
            values.put(MediaStore.MediaColumns.IS_PENDING, 0);
            application.getContentResolver().update(uri, values, null, null);
            return "下载/SmartTube-Proxy/" + fileName;
        } catch (Throwable error) {
            application.getContentResolver().delete(uri, null, null);
            return "导出失败：" + error.getMessage();
        }
    }

    public static State getState() {
        return STATE.get();
    }

    public static String getLastError() {
        return lastError;
    }

    public static void ensureStarted(Context context, ResultCallback callback) {
        State state = STATE.get();
        if (state == State.RUNNING) {
            callback.onResult("", null);
            return;
        }
        if (state == State.FAILED) {
            callback.onResult(null, lastError == null ? "Mihomo 初始化失败" : lastError);
            return;
        }
        start(context.getApplicationContext());
        pollUntilStarted(System.currentTimeMillis() + 15_000, callback);
    }

    private static void pollUntilStarted(long deadline, ResultCallback callback) {
        State state = STATE.get();
        if (state == State.RUNNING) {
            callback.onResult("", null);
            return;
        }
        if (state == State.FAILED) {
            callback.onResult(null, lastError == null ? "Mihomo 初始化失败" : lastError);
            return;
        }
        if (System.currentTimeMillis() >= deadline) {
            callback.onResult(null, "Mihomo 初始化超时");
            return;
        }
        new Handler(Looper.getMainLooper()).postDelayed(
                () -> pollUntilStarted(deadline, callback), 200);
    }

    public static void validateConfig(File file, ResultCallback callback) {
        invoke("validateConfig", file.getAbsolutePath(), (data, error) -> {
            String validationError = error != null ? error : emptyToNull(data);
            callback.onResult("", validationError);
        });
    }

    public static void reloadConfig(File source, Map<String, String> selectedMap,
                                    ResultCallback callback) {
        if (!RELOADING.compareAndSet(false, true)) {
            callback.onResult(null, "正在应用配置，请稍后重试");
            return;
        }
        getProxies((snapshot, snapshotError) -> {
        String previousNode = "REJECT";
        if (snapshotError == null) {
            try {
                JSONObject global = new JSONObject(snapshot).getJSONObject("proxies").optJSONObject("GLOBAL");
                if (global != null) previousNode = global.optString("now", "REJECT");
            } catch (JSONException ignored) { }
        }
        rollbackSelection = Collections.singletonMap("GLOBAL", previousNode);
        EXECUTOR.execute(() -> {
            try {
                prepareRuntimeConfig(source, selectedMap, (data, error) -> {
                    RELOADING.set(false);
                    callback.onResult(data, error);
                });
            } catch (Exception error) {
                RELOADING.set(false);
                callback.onResult(null, "准备配置失败，请导出诊断日志");
            }
        });
        });
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

    public static void closeConnections(ResultCallback callback) {
        invoke("closeAllConnections", "", callback);
    }

    private static void startInternal(Context context) {
        try {
            recordStage(context, "正在准备初始化配置");
            File home = new File(context.getFilesDir(), HOME_DIRECTORY);
            ensureDirectory(home);
            File config = new File(home, CONFIG_FILE);
            writeBootstrapConfigIfMissing(config);

            recordStage(context, "准备在 Android 主线程加载 native 库");
            Clash.INSTANCE.load(context.getApplicationInfo().nativeLibraryDir,
                    stage -> recordStage(context, stage));
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
                ResultCallback bootstrapReady = (ignored, error) -> {
                    if (error != null) {
                        fail("Unable to enforce local Mihomo runtime", null);
                        return;
                    }
                    recordStage(context, "正在等待 127.0.0.1:7890");
                    EXECUTOR.execute(() -> {
                        awaitLoopbackProxy();
                    });
                };
                // Bootstrap is our own closed config. Do not force GLOBAL/DIRECT before
                // the selected subscription and node have been applied.
                bootstrapReady.onResult("", null);
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
        File temporary = new File(target.getParentFile(), CONFIG_FILE + ".tmp");
        File previous = new File(target.getParentFile(), CONFIG_FILE + ".previous");
        try {
            RuntimeConfigPolicy.writeSafe(source, temporary);
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
                "{\"mixed-port\":7890,\"port\":0,\"socks-port\":0,\"redir-port\":0,\"tproxy-port\":0,\"allow-lan\":false," +
                        "\"mode\":\"global\",\"tun\":{\"enable\":false}}",
                callback);
    }

    private static void rollback(File previous, File target, ResultCallback callback,
                                 String originalError) {
        if (!previous.isFile()) {
            markRollbackFailed();
            callback.onResult(null, originalError + "；旧配置不可恢复，请重启应用");
            return;
        }
        try {
            copyFile(previous, target);
            previous.delete();
            if (STATE.get() == State.FAILED) {
                callback.onResult(null, originalError + "；核心状态未知，请重启应用");
                return;
            }
            applyRuntimeConfig(rollbackSelection, (ignored, rollbackError) -> {
                if (rollbackError != null) {
                    markRollbackFailed();
                }
                callback.onResult(null, originalError);
            });
        } catch (IOException ignored) {
            markRollbackFailed();
            callback.onResult(null, originalError + "；旧配置恢复失败，请重启应用");
        }
    }

    private static void markRollbackFailed() {
        STATE.set(State.FAILED);
        lastError = "旧配置恢复失败，请重启应用";
        // The failed new configuration may still be live. Close its global route
        // rather than advertising a healthy old connection that was not restored.
        changeProxy("GLOBAL", "REJECT", (data, error) -> closeConnections((ignored, closeError) -> { }));
    }

    public static void acceptRuntime() {
        EXECUTOR.execute(() -> new File(runtimeConfig().getParentFile(), CONFIG_FILE + ".previous").delete());
    }

    /** Undo a successful YAML load if restoring the actual selected node failed. */
    public static void rejectRuntime(String reason, ResultCallback callback) {
        EXECUTOR.execute(() -> rollback(new File(runtimeConfig().getParentFile(), CONFIG_FILE + ".previous"),
                runtimeConfig(), callback, reason));
    }

    private static void invoke(String method, String data, ResultCallback callback) {
        if (!Clash.INSTANCE.isLoaded()) {
            callback.onResult(null, "Mihomo JNI bridge is not loaded");
            return;
        }
        AtomicBoolean finished = new AtomicBoolean();
        Handler handler = new Handler(Looper.getMainLooper());
        Runnable timeout = () -> {
            if (finished.compareAndSet(false, true)) {
                if ("setupConfig".equals(method)) {
                    lastError = "配置应用超时，核心状态未知，请重启应用";
                    STATE.set(State.FAILED);
                }
                callback.onResult(null, "Mihomo 操作超时：" + method);
            }
        };
        handler.postDelayed(timeout, "setupConfig".equals(method) ? 30_000 : 12_000);
        ResultCallback once = (result, error) -> {
            if (finished.compareAndSet(false, true)) {
                handler.removeCallbacks(timeout);
                callback.onResult(result, ProxyErrors.redact(error));
            }
        };
        try {
            JSONObject action = new JSONObject();
            action.put("id", Long.toString(ACTION_IDS.incrementAndGet()));
            action.put("method", method);
            action.put("data", data == null ? "" : data);
            Clash.INSTANCE.invokeAction(action.toString(), new InvokeInterface() {
                @Override
                public void onResult(String result) {
                    parseActionResult(result, once);
                }
            });
        } catch (Throwable error) {
            once.onResult(null, "Mihomo 调用失败：" + error.getClass().getSimpleName());
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
        // Always bootstrap a closed, minimal listener. Never execute an old subscription
        // before the current policy has sanitized it (including upgrades from old builds).
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
        Context application = context.getApplicationContext();
        application.getSharedPreferences(DIAGNOSTIC_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_DIAGNOSTIC_STAGE, stage)
                .putLong(KEY_DIAGNOSTIC_TIME, System.currentTimeMillis())
                .commit();
        appendDiagnostic(application, stage);
    }

    private static void appendDiagnostic(Context context, String text) {
        if (context == null || text == null) {
            return;
        }
        File file = new File(context.getFilesDir(), DIAGNOSTIC_LOG_FILE);
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
                .format(new Date());
        try (FileOutputStream output = new FileOutputStream(file, true)) {
            output.write((timestamp + " | " + ProxyErrors.redact(text) + "\n").getBytes(StandardCharsets.UTF_8));
            output.flush();
            output.getFD().sync();
        } catch (IOException ignored) {
            Log.e(TAG, "Unable to append Mihomo diagnostic log", ignored);
        }
    }

    private static String buildDiagnosticReport(Context context) throws IOException {
        StringBuilder report = new StringBuilder();
        report.append("SmartTube Proxy - Mihomo diagnostics\n");
        report.append("Generated: ").append(new Date()).append('\n');
        report.append("Manufacturer: ").append(Build.MANUFACTURER).append('\n');
        report.append("Model: ").append(Build.MODEL).append('\n');
        report.append("Device: ").append(Build.DEVICE).append('\n');
        report.append("Android SDK: ").append(Build.VERSION.SDK_INT).append('\n');
        report.append("Android release: ").append(Build.VERSION.RELEASE).append('\n');
        report.append("Supported ABIs: ");
        for (int index = 0; index < Build.SUPPORTED_ABIS.length; index++) {
            if (index > 0) {
                report.append(", ");
            }
            report.append(Build.SUPPORTED_ABIS[index]);
        }
        report.append('\n');
        report.append("Native library dir: ")
                .append(context.getApplicationInfo().nativeLibraryDir).append('\n');
        report.append("Current state: ").append(STATE.get()).append('\n');
        report.append("Last persisted stage: ").append(getStartupDiagnostic(context)).append('\n');
        report.append("Last error: ").append(lastError == null ? "none" : lastError).append('\n');
        report.append("\n--- Timeline ---\n");

        File file = new File(context.getFilesDir(), DIAGNOSTIC_LOG_FILE);
        if (file.isFile()) {
            try (FileInputStream input = new FileInputStream(file)) {
                byte[] buffer = new byte[16 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    report.append(new String(buffer, 0, count, StandardCharsets.UTF_8));
                }
            }
        } else {
            report.append("No timeline recorded.\n");
        }
        return report.toString();
    }

    private static String stackTrace(Throwable error) {
        if (error == null) {
            return "No throwable supplied";
        }
        StringWriter writer = new StringWriter();
        error.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }

    private static void fail(String message, Throwable error) {
        lastError = ProxyErrors.redact(error == null || error.getMessage() == null
                ? message : message + ": " + error.getMessage());
        STATE.set(State.FAILED);
        recordStage(appContext, "初始化失败：" + lastError);
        if (error != null) {
            appendDiagnostic(appContext, stackTrace(error));
        }
        Log.e(TAG, lastError);
    }
}
