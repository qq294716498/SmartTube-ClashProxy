# Phase 3: subscriptions and runtime proxy control

## Phase 3A API verification

The pinned libmihomo-android v0.3.3 exposes the required operations through
the existing JNI `invokeAction` bridge. No external controller port, Web UI,
VPN service, or TUN integration is required.

| Operation | JNI action | Data |
| --- | --- | --- |
| Validate YAML | `validateConfig` | Absolute temporary file path |
| Load/reload YAML | `setupConfig` | JSON containing `selected-map` |
| Enforce app proxy settings | `updateConfig` | Mixed port 7890, GLOBAL mode, LAN/TUN disabled |
| Query runtime proxies | `getProxies` | Empty string |
| Select node | `changeProxy` | Selector group and proxy name |
| HTTPS delay test | `testDelay` | Proxy name, HTTPS URL and timeout |

The action results use the bridge envelope `{id, method, data, code}`. A zero
code is success. Config actions additionally return an empty data string on
success and an error string otherwise.

## Storage and privacy

- Subscription metadata is stored only in app-private SharedPreferences.
- Each subscription uses its own `files/proxy/subscriptions/sub_<uuid>/config.yaml`.
- URLs are never logged and are masked in diagnostics.
- Updates download to `config.tmp`, validate through Mihomo, then atomically
  replace the last valid config. A failed update keeps the old file.
- No subscription URL, token, node password, UUID, or private key is present in
  source, tests, CI, or this report.

## Runtime behavior

- One subscription ID is active at a time; names are not identifiers.
- Switching copies the selected subscription into Mihomo's runtime home and
  calls `setupConfig` without restarting SmartTube.
- Runtime `GLOBAL` is preferred as the controlling selector. If unavailable,
  the first appropriate selector is used.
- Node lists come from `getProxies`, not from ad-hoc YAML parsing.
- Delay tests use Mihomo URLTest against an HTTPS connectivity endpoint, with
  a five-second timeout, five concurrent tests, and a five-minute cache.
- Selected group, node and AUTO/MANUAL mode are stored per subscription.
- SmartTube's existing HTTP/OkHttp proxy chain is pointed to
  `127.0.0.1:7890` only while the embedded proxy switch is enabled.

## Build verification

- GitHub Actions: `Build SmartTube Proxy` run 30 completed successfully.
- Run: https://github.com/qq294716498/SmartTube-Proxy/actions/runs/35454505056
- Verified commit: `bf2eab2204323b3a2434b74f55cc2200115f6635`
- Both `lintStproxyDebug` and clean `assembleStproxyDebug` completed successfully.
- ARM64, ARMv7, and Universal APK artifacts were uploaded with seven-day
  retention and expire on 2026-09-26.
- Universal APK SHA-256:
  `0bf1595bd9fe0f35a2ba71e55ce11923eac34a94fd3b20ca16d8e28a18fcec05`
- The Universal APK contains both ARM64 and ARMv7 `libclash.so` and
  `libmihomo-jni.so` binaries.

Runtime subscription download, provider compatibility, node switching, delay
results, and D-pad behavior still require an ARM Android TV/device because CI
does not provide a Mihomo-capable TV runtime.
