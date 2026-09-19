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
    public static final Clash INSTANCE = new Clash();

    private static final int EXPECTED_BRIDGE_ABI = 3;

    private volatile boolean loaded;
    private volatile Throwable initFailure;

    private Clash() {
    }

    public synchronized void load(String nativeLibraryDirectory) {
        if (loaded) {
            return;
        }

        try {
            System.load(resolveLibrary(nativeLibraryDirectory, "libclash.so"));
            System.load(resolveLibrary(nativeLibraryDirectory, "libmihomo-jni.so"));
            int actualAbi = nativeBridgeABI();
            if (actualAbi != EXPECTED_BRIDGE_ABI) {
                throw new IllegalStateException(
                        "libclash bridge ABI mismatch: expected " +
                                EXPECTED_BRIDGE_ABI + ", found " + actualAbi);
            }
            loaded = true;
            initFailure = null;
        } catch (Throwable error) {
            initFailure = error;
        }
    }

    public boolean isLoaded() {
        return loaded;
    }

    public void quickSetup(String initParams, String setupParams, InvokeInterface callback) {
        assertReady();
        nativeQuickSetup(initParams, setupParams, callback);
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

    private static native void nativeQuickSetup(
            String initParams,
            String setupParams,
            InvokeInterface callback);
}
