# SmartTube Proxy — Phase 0/1 Report

Date: 2026-09-19 (UTC)

## 1. Frozen upstream baseline

- Upstream: `https://github.com/yuliskov/SmartTube.git`
- Branch: `master`
- Commit: `c75606267ec948462818041879e3b7110fd918bc`
- Commit subject: `bump 32.53`
- App version: `32.53`
- Local development branch: `feature/mihomo-proxy`
- Remote layout: `upstream` points to the official SmartTube repository. `origin` is intentionally left unset until the private GitHub repository is authorized/created.

Submodules are clean and pinned to:

- `MediaServiceCore`: `59795dfe138b6b44b4ac8ca639c1596bd59e17b8`
- `SharedModules`: `13f5687dd6757b02fbcdf14c5403d0339e377db5`
- `MediaServiceCore/SharedModules`: `13f5687dd6757b02fbcdf14c5403d0339e377db5`

## 2. Original build result

The unmodified baseline passed the official GitHub Actions workflow on the exact frozen commit:

- Workflow: `.github/workflows/CI.yml` (`Build Debug APK`)
- Run: `35412468460`
- Run number: `997`
- Head SHA: `c75606267ec948462818041879e3b7110fd918bc`
- Result: `success`
- Steps: recursive checkout, JDK 17, `lintStbetaRelease`, `clean assembleStbetaRelease`, artifact upload
- Produced artifacts: arm64-v8a, armeabi-v7a, x86, universal, and lint report

The exact arm64 artifact from that successful workflow run was downloaded and verified:

- File: `artifacts/phase0/nightly-arm64/SmartTube_beta_32.53-nightly-997_arm64-v8a.apk`
- SHA-256: `669fefa7372e61261dd18585c0f71d145e4d0aeb3651692997f738237612316a`
- Artifact archive SHA-256: `9adb1faa96870a71e6a875324b99ed2f7fa11b6c6b62d66f804c72ceb8fd47a3`

The current execution container cannot connect to `services.gradle.org`, so a duplicate local invocation stops while downloading Gradle 7.5 before Gradle evaluates the source. This is an executor network restriction, not a source/build failure. The exact-commit GitHub Actions build above is the Phase 0 build proof; the private repository will reuse that proven environment.

Build toolchain:

- JDK: 17 (official CI)
- Gradle wrapper: 7.5
- Android Gradle Plugin: 7.4.2
- Kotlin: 1.8.10
- compileSdk: 34
- buildTools: 30.0.3
- minSdk: 17 upstream; proxy flavor must be minSdk 21 because the Mihomo native library requires API 21
- NDK declared by SmartTube: 21.0.6113669
- Original build command: `./gradlew clean assembleStbetaRelease`
- Original APK output: `smarttubetv/build/outputs/apk/stbeta/release/`

A signed upstream beta APK was downloaded as a Phase 0 reference artifact:

- File: `artifacts/phase0/smarttube_beta_32.53_upstream.apk`
- SHA-256: `ad52ebbb814602b7faf7cdf6c45488e327c038b344025b93d810aaf6acc5bd87`

This reference APK is not the future SmartTube Proxy deliverable.

## 3. SmartTube network architecture

### Startup and current proxy configuration

- `smarttubetv/src/main/java/com/liskovsoft/smartyoutubetv2/tv/ui/main/MainApplication.java`
  - Earliest application lifecycle point. Suitable for loading native libraries, but proxy startup must be asynchronous and coordinated with the splash screen.
- `common/src/main/java/com/liskovsoft/smartyoutubetv2/common/app/presenters/SplashPresenter.java`
  - Current order is `initGlobalPrefs()` then `initProxy()` before other one-time work.
  - This is the correct readiness gate to wait for Mihomo before API clients are first used.
- `common/src/main/java/com/liskovsoft/smartyoutubetv2/common/proxy/ProxyManager.java`
  - Existing HTTP/SOCKS support writes process-local JVM proxy properties and notifies Chromium/WebView proxy listeners.
  - It does not use `VpnService`, TUN, root, iptables, or a device-wide proxy.
  - The built-in Mihomo path should reuse this class with fixed internal HTTP proxy `127.0.0.1:7890`; the user must never enter host/port.
- `common/src/main/java/com/liskovsoft/smartyoutubetv2/common/proxy/WebProxyDialog.java`
  - Existing manual proxy UI. It should remain available only for upstream/manual behavior or be hidden in the proxy flavor to avoid conflict with the new managed Mihomo UI.

### API and shared HTTP clients

- `MediaServiceCore/youtubeapi/src/main/java/com/liskovsoft/googlecommon/common/helpers/RetrofitHelper.java`
  - Creates YouTube/Google Retrofit services.
- `MediaServiceCore/youtubeapi/src/main/java/com/liskovsoft/googlecommon/common/helpers/RetrofitOkHttpHelper.kt`
  - Builds the API client from `OkHttpManager.instance().client.newBuilder()`.
- `SharedModules/sharedutils/src/main/java/com/liskovsoft/sharedutils/okhttp/OkHttpManager.java`
  - Process singleton used by APIs and optionally by the player.
- `SharedModules/sharedutils/src/main/java/com/liskovsoft/sharedutils/okhttp/OkHttpCommons.java`
  - Central OkHttp builder configuration.
  - Explicit `setupProxy(okBuilder)` is currently commented out, so proxy behavior is indirect through JVM/system proxy properties and the default proxy selector.
  - For deterministic app-only proxying, Phase 3 should add an explicit, runtime-owned proxy provider/factory and invalidate the singleton after switching configurations.

### Images

- Card, channel, background, storyboard, chat, sign-in, and other images use Glide.
- `smarttubetv/src/main/java/com/liskovsoft/smartyoutubetv2/tv/util/GlideCachingModule.java` only configures caching; it does not install an OkHttp integration.
- Glide therefore uses its default URL connection path. The existing `ProxyManager` process properties are expected to affect it, but this must be verified with `ytimg.com` during Phase 3. If unreliable on the target TV, add a dedicated Glide OkHttp module using the same proxied client.

### Update traffic

- `SharedModules/appupdatechecker2/.../DownloadManager.java` uses OkHttp.
- `SharedModules/appupdatechecker2/.../DownloadListener.java` also contains an `HttpURLConnection` path.
- Official update entry points are `SplashPresenter`, `BootDialogPresenter`, `AppUpdatePresenter`, and the About presenters.
- The proxy flavor must disable official update checks/install actions so an official APK cannot replace the custom build.

## 4. Player and real video-segment path

Primary factory:

- `common/src/main/java/com/liskovsoft/smartyoutubetv2/common/exoplayer/ExoMediaSourceFactory.java`

Flow:

1. Video metadata and stream URLs are obtained through MediaServiceCore/YouTube API.
2. `ExoMediaSourceFactory` creates DASH, SABR, HLS, SmoothStreaming, or extractor media sources.
3. DASH/SABR chunk factories and HLS media sources all receive the same cached `mMediaDataSourceFactory`.
4. The HTTP transport is selected from three paths:
   - OkHttp: `OkHttpDataSourceFactory(OkHttpManager.instance().getClient())`
   - Cronet: `CronetDataSourceFactory(CronetManager.getEngine(...))`
   - Default: `DefaultHttpDataSourceFactory`, backed by Android URL connections
5. Actual `googlevideo.com` audio/video segment requests pass through these data-source factories, not through Retrofit.

Subtitles are written into the generated MPD by:

- `MediaServiceCore/youtubeapi/src/main/java/com/liskovsoft/youtubeapi/formatbuilders/mpdbuilder/YouTubeMPDBuilder.java`

Their `youtube.com/api/timedtext` URLs are consequently fetched through the same ExoPlayer media data source.

Critical Phase 4 decision: while embedded proxy mode is enabled, force the player to the explicitly proxied OkHttp data source. Do not allow Cronet or the default URL-connection path to bypass the local proxy. Switching/reloading Mihomo must also invalidate cached `ExoMediaSourceFactory` and OkHttp clients/connections so subsequent seeks and next-video playback use the new route.

## 5. Mihomo integration decision

Evaluated candidates:

1. `oviron/libmihomo-android` v0.3.3
   - Bundles Mihomo v1.19.30.
   - Signed release AAR SHA-256: `3bccc9020926b0b7bf1fd09553b41e4678c80cedc5cbe61d90a78fe4b71aca76`.
   - ABIs: arm64-v8a, armeabi-v7a, x86_64.
   - minSdk 21.
   - Exposes init/config load, proxy enumeration, selector changes, delay tests, provider updates, status/events, and listener control through JNI/action APIs.
   - GPL-3.0; the resulting combined app must be distributed with corresponding GPL source obligations.
   - API is pre-1.0 and must be pinned exactly.
2. MetaCubeX ClashMetaForAndroid in-tree core
   - Mature Android integration and supports arm64-v8a, armeabi-v7a, x86, x86_64.
   - Strong reference implementation, but importing its complete modern Gradle/Kotlin/cgo module into SmartTube is substantially more invasive.
3. `Animeblin1/libmihomo-android` v1.19.31
   - Very small C API and current core, but no AAR/Kotlin facade, no direct node/query/delay API, and no established adoption. Node management would depend on the external-controller REST API.

Selected direction for the PoC: pin `oviron/libmihomo-android` v0.3.3 and its verified native libraries, with a minimal Java-8-compatible facade if the upstream Kotlin facade causes AGP 7.4/D8 incompatibility. Do not compile Mihomo from scratch inside the SmartTube build in the first PoC.

The proxy build should initially emit only arm64-v8a and armeabi-v7a. The selected AAR has no 32-bit x86 library; producing an x86 proxy APK would create a runtime failure. The target ThunderBird TV should use arm64-v8a, but this must be confirmed from the installed APK/device ABI during Phase 10.

Expected core startup for the no-VPN design:

1. Copy the active validated subscription to Mihomo home as `config.yaml`.
2. Enforce/override `mixed-port: 7890`, `allow-lan: false`, loopback binding, `mode: global`, TUN disabled, and safe log level.
3. Load `libclash.so` and `libmihomo-jni.so` once.
4. Initialize Mihomo and apply the profile.
5. Start/update listeners without calling `startTUN()`.
6. Poll-connect `127.0.0.1:7890` until ready or timeout.
7. Configure SmartTube's process-local HTTP proxy and release the splash readiness gate.

## 6. Planned files and modules

New isolated package under `common/src/main/java/com/liskovsoft/smartyoutubetv2/common/proxy/mihomo/`:

- `MihomoCoreManager`: native load, config apply/reload, local-port readiness, lifecycle/state
- `SubscriptionManager`: CRUD, atomic download/validate/promote, last-known-good rollback
- `ProxyConfigManager`: safe YAML preparation/override and per-subscription file layout
- `ProxyNodeManager`: group/node query, selection, delay tests, auto-select
- `ProxyHealthChecker`: core/local proxy/API/googlevideo/DNS diagnostic probes
- `ProxyPreferences`: enabled state, active subscription ID, per-subscription last node; never logs secrets
- `SecretRedactor`: URL/token/password redaction for logs and diagnostics

Expected upstream touch points:

- `smarttubetv/build.gradle`: new `stproxy` flavor, application ID, minSdk 21, ABI filters, pinned Mihomo dependency/native libraries, APK name
- `smarttubetv/src/stproxy/res/values/strings.xml`: `SmartTube Proxy` label
- `smarttubetv/src/main/java/.../MainApplication.java`: early native library initialization only
- `common/.../SplashPresenter.java`: asynchronous core readiness before network warm-up
- `common/.../proxy/ProxyManager.java`: internal loopback-proxy method and deterministic reset/disable behavior
- `SharedModules/.../okhttp/OkHttpCommons.java`: explicit process-owned HTTP proxy hook
- `SharedModules/.../okhttp/OkHttpManager.java`: safe client invalidation/connection-pool eviction
- `common/.../exoplayer/ExoMediaSourceFactory.java`: force proxied OkHttp for embedded mode and rebuild factories after route change
- `common/.../misc/AppDataSourceManager.java`: add the TV-native `Network Proxy` settings entry
- New settings presenters/dialog models for subscription, node, and diagnostics pages
- `common/.../presenters/dialogs/BootDialogPresenter.java`, `AppUpdatePresenter.java`, About presenters: disable official update install path for `stproxy`
- `.github/workflows/proxy-build.yml`: pinned dependency verification, build, signing secrets, APK checksums, arm64/universal artifacts

## 7. Principal risks

- The exact target requirement is not merely API proxying: ExoPlayer's three selectable transports can diverge. Embedded mode must use one explicitly proxied transport.
- Native library adds roughly 40–45 MB per ABI before APK compression. Prefer arm64 output for the TV and avoid a needlessly large universal artifact for routine installs.
- The selected native library is young and pre-1.0. Pin release plus SHA-256 and retain a fallback option to the ClashMetaForAndroid bridge.
- Existing manual proxy settings and embedded proxy settings can conflict. Embedded mode needs precedence and a reversible migration.
- Switching subscription/node while old HTTP/2 connections and ExoPlayer factories remain cached can keep traffic on the old route. Pools/factories must be invalidated.
- DNS must not be pre-resolved by SmartTube for HTTP CONNECT requests. Tests must cover `youtube.com`, `ytimg.com`, and especially `googlevideo.com` segments.
- Subscription configs may rely on external GeoIP/GeoSite/provider files. The first implementation must preserve last-known-good files and surface missing-resource errors without destroying the active config.
- GPL-3.0 obligations apply to a distributed APK containing Mihomo.

## 8. Current blocker

The GitHub connector is installed and can access account `qq294716498`, but it cannot create a repository and no SmartTube Proxy repository exists yet. The user needs to create one empty private repository (recommended name: `SmartTube-Proxy`, with no generated README/license/gitignore). After it exists, it can become `origin` and receive the frozen baseline, custom workflow, build artifacts, and later signing-secret configuration.

No real subscription URL or TV access is needed for Phase 2–9 development.
