package io.github.oviron.libmihomo;

/**
 * Callback contract required by libmihomo-jni during JNI_OnLoad.
 *
 * The native bridge resolves this class and both method signatures even when
 * SmartTube only uses the local mixed proxy and does not start a TUN device.
 * Removing or renaming it causes a fatal native crash while loading
 * libmihomo-jni.so.
 */
public interface TunInterface {
    void protect(int fd);

    String resolverProcess(int protocol, String source, String target, int uid);
}
