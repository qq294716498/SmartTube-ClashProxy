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
exact release and refuses to install it unless its SHA-256 matches. SmartTube's
current D8 cannot consume the AAR's newer Kotlin facade, so the script extracts
only the verified native libraries. A flavor-local Java 8 facade preserves the
same JNI class names, method names, and bridge ABI without changing SmartTube's
Gradle/AGP baseline.

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
uploaded artifact for 7 days. Superseded builds on the same branch are cancelled
automatically.

Definitive verified build:

- Commit: `f14c382c5ec19c18fade3e08e318373805469df9`
- Workflow run: `35451386803` (run 10)
- `./gradlew lintStproxyDebug`: passed
- `./gradlew clean assembleStproxyDebug`: passed
- ARM64 artifact: `10586881720`
- ARMv7 artifact: `10587096543`
- Universal artifact: `10586791929`
- Artifact expiry: 2026-09-26 (7 days after creation)
- Universal APK SHA-256:
  `eaf57e3025a54f731f16e6dbd383adee0d8c68332feba21953afd7f6c60d2e9a`

The downloaded universal artifact was independently checked with
`sha256sum -c`. Its APK contains both `libclash.so` and `libmihomo-jni.so` for
ARM64 and ARMv7, plus the compiled bootstrap/manager code and the local-only
`127.0.0.1:7890` configuration.

## Remaining runtime acceptance check

Compilation, lint, packaging, native payloads, and the readiness state machine
are verified. The manager reports `RUNNING` only after an actual TCP connection
to `127.0.0.1:7890` succeeds. Executing that Android process requires an ARM
Android TV/device (the Phase 2 artifact intentionally does not ship an x86
emulator ABI), so the final live-port observation is the device-only acceptance
check before Phase 2 can be marked runtime-complete.
