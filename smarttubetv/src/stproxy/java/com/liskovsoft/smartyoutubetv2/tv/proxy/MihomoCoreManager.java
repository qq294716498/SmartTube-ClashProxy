package com.liskovsoft.smartyoutubetv2.tv.proxy;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import io.github.oviron.libmihomo.Clash;
import io.github.oviron.libmihomo.InvokeInterface;

/** Minimal Phase 2 lifecycle wrapper around libmihomo-android. */
public final class MihomoCoreManager {
    public enum State {
        STOPPED,
        STARTING,
        RUNNING,
        FAILED
    }

    public static final String LOOPBACK_HOST = "127.0.0.1";
    public static final int MIXED_PORT = 7890;

    private static final String TAG = "MihomoCoreManager";
    private static final String HOME_DIRECTORY = "mihomo";
    private static final String CONFIG_FILE = "config.yaml";
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
    private static final AtomicReference<State> STATE =
            new AtomicReference<>(State.STOPPED);
    private static final ExecutorService EXECUTOR =
            Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "smarttube-mihomo");
                thread.setDaemon(true);
                return thread;
            });

    private static volatile String lastError;

    private MihomoCoreManager() {
    }

    public static void start(Context context) {
        if (!START_REQUESTED.compareAndSet(false, true)) {
            return;
        }

        STATE.set(State.STARTING);
        EXECUTOR.execute(() -> startInternal(context.getApplicationContext()));
    }

    public static State getState() {
        return STATE.get();
    }

    public static String getLastError() {
        return lastError;
    }

    private static void startInternal(Context context) {
        try {
            File home = new File(context.getFilesDir(), HOME_DIRECTORY);
            ensureDirectory(home);
            File config = new File(home, CONFIG_FILE);
            writeBootstrapConfigIfMissing(config);

            Clash.INSTANCE.load(context.getApplicationInfo().nativeLibraryDir);
            if (!Clash.INSTANCE.isLoaded()) {
                throw new IllegalStateException("libmihomo native libraries failed to load");
            }

            String initParams = "{\"home-dir\":\"" + jsonEscape(home.getAbsolutePath()) +
                    "\",\"version\":" + Build.VERSION.SDK_INT + "}";

            Clash.INSTANCE.quickSetup(initParams, "{}", new InvokeInterface() {
                @Override
                public void onResult(String result) {
                    if (result != null && !result.isEmpty()) {
                        fail("Mihomo setup failed: " + result, null);
                        return;
                    }
                    EXECUTOR.execute(MihomoCoreManager::awaitLoopbackProxy);
                }
            });
        } catch (Throwable error) {
            fail("Mihomo initialization failed", error);
        }
    }

    private static void awaitLoopbackProxy() {
        long deadline = System.currentTimeMillis() + READY_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(LOOPBACK_HOST, MIXED_PORT),
                        CONNECT_TIMEOUT_MS);
                lastError = null;
                STATE.set(State.RUNNING);
                Log.i(TAG, "Mihomo is ready on " + LOOPBACK_HOST + ":" + MIXED_PORT);
                return;
            } catch (IOException ignored) {
                try {
                    Thread.sleep(RETRY_DELAY_MS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    fail("Interrupted while waiting for Mihomo", interrupted);
                    return;
                }
            }
        }
        fail("Mihomo did not open the loopback proxy before timeout", null);
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
        byte[] bytes = PHASE_TWO_CONFIG.getBytes(StandardCharsets.UTF_8);
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            output.write(bytes);
            output.flush();
            output.getFD().sync();
        }

        if (!temporary.renameTo(config)) {
            throw new IOException("Unable to install Mihomo bootstrap config");
        }
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void fail(String message, Throwable error) {
        lastError = error == null || error.getMessage() == null
                ? message
                : message + ": " + error.getMessage();
        STATE.set(State.FAILED);
        if (error == null) {
            Log.e(TAG, message);
        } else {
            Log.e(TAG, message, error);
        }
    }
}
