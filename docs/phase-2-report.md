# Phase 2: embedded Mihomo minimum integration

## Scope

This phase adds a dedicated `stproxy` flavor and starts an embedded Mihomo core
inside the SmartTube application process. It does not yet route SmartTube's
network clients through the local proxy; that is Phase 3/4 work.

## Pinned dependency

- Wrapper: `oviron/libmihomo-android` v0.3.3
- Bundled Mihomo: v1.19.30
- Bridge ABI: 3
- SHA-256: `3bccc9020926b0b7bf1fd09553b41e4678c80cedc5cbe61d90a78fe4b71aca76`
- Packaged ABIs: `arm64-v8a`, `armeabi-v7a`
- The upstream AAR also contains `x86_64`, but the SmartTube proxy flavor does
  not build an x86/x86_64 TV APK in this phase.

The AAR is not committed to Git. `scripts/fetch-libmihomo.sh` downloads the
exact release and refuses to install it unless its SHA-256 matches.

## Runtime path

1. `MainApplication.onCreate()` calls the flavor-safe `MihomoBootstrap`.
2. Official SmartTube flavors return immediately and do not package Mihomo.
3. The `stproxy` flavor reflects into `MihomoCoreManager`.
4. The manager creates an app-private `files/mihomo/config.yaml` only when one
   does not already exist.
5. `Clash.load(nativeLibraryDir)` loads `libclash.so` and
   `libmihomo-jni.so`.
6. `Clash.quickSetup` initializes the core with the app-private home.
7. A bounded readiness probe waits for `127.0.0.1:7890`.
8. Startup reaches `RUNNING` only after a TCP connection to that loopback port
   succeeds. Failures are contained and do not crash SmartTube.

The bootstrap profile is deliberately local-only:

```yaml
mixed-port: 7890
allow-lan: false
bind-address: 127.0.0.1
mode: global
ipv6: false
tun:
  enable: false
```

It contains no subscription URL, token, node credential, TUN, VPN service, or
system-wide proxy setting.

## Build and artifacts

`.github/workflows/proxy-build.yml` builds `assembleStproxyDebug`, produces
ARM64, ARMv7 and universal APK artifacts, writes SHA-256 sums, and retains every
uploaded artifact for 7 days.

The definitive Phase 2 build result and workflow run ID are recorded after the
GitHub Actions build completes.
