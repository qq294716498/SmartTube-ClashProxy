package io.github.oviron.libmihomo;

import java.io.File;

/**
 * Java 8 facade for libmihomo's stable JNI ABI.
 *
 * The upstream AAR facade is built with a newer Kotlin toolchain than
 * SmartTube's current D8 can parse. Native libraries still expose the stable
 * v3 JNI ABI, so this flavor-local facade keeps the application toolchain
 * unchanged while preserving the native class and method names.
 */
public final class Clash {
    public interface LoadObserver {
        void onStage(String stage);
    }

    public static final Clash INSTANCE = new Clash();

    private static final int EXPECTED_BRIDGE_ABI = 3;

    private volatile boolean loaded;
    private volatile Throwable initFailure;

    private Clash() {
    }

    public synchronized void load(String nativeLibraryDirectory) {
        load(nativeLibraryDirectory, stage -> { });
    }

    public synchronized void load(String nativeLibraryDirectory, LoadObserver observer) {
        if (loaded) {
            observer.onStage("Mihomo native 库已经加载");
            return;
        }

        try {
            observer.onStage("正在加载 libclash.so");
            System.load(resolveLibrary(nativeLibraryDirectory, "libclash.so"));
            observer.onStage("libclash.so 加载成功");

            observer.onStage("正在加载 libmihomo-jni.so");
            System.load(resolveLibrary(nativeLibraryDirectory, "libmihomo-jni.so"));
            observer.onStage("libmihomo-jni.so 加载成功");

            observer.onStage("正在校验 JNI bridge ABI");
            int actualAbi = nativeBridgeABI();
            if (actualAbi != EXPECTED_BRIDGE_ABI) {
                throw new IllegalStateException(
                        "libclash bridge ABI mismatch: expected " +
                                EXPECTED_BRIDGE_ABI + ", found " + actualAbi);
            }
            loaded = true;
            initFailure = null;
            observer.onStage("JNI bridge ABI 校验成功");
        } catch (Throwable error) {
            initFailure = error;
            observer.onStage("native 库加载失败：" +
                    (error.getMessage() == null ? error.getClass().getName() : error.getMessage()));
        }
    }

    public boolean isLoaded() {
        return loaded;
    }

    public void quickSetup(String initParams, String setupParams, InvokeInterface callback) {
        assertReady();
        nativeQuickSetup(initParams, setupParams, callback);
    }

    public void invokeAction(String action, InvokeInterface callback) {
        assertReady();
        nativeInvokeAction(action, callback);
    }

    private void assertReady() {
        if (loaded) {
            return;
        }
        throw new IllegalStateException("Mihomo JNI bridge is not loaded", initFailure);
    }

    private static String resolveLibrary(String directory, String name) {
        File library = new File(directory, name);
        if (!library.isFile()) {
            throw new IllegalStateException("Missing native library: " + library);
        }
        return library.getAbsolutePath();
    }

    private static native int nativeBridgeABI();

    private static native void nativeInvokeAction(String action, InvokeInterface callback);

    private static native void nativeQuickSetup(
            String initParams,
            String setupParams,
            InvokeInterface callback);
}
