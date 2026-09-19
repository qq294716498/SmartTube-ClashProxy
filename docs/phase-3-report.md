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

The definitive GitHub Actions run, APK artifacts, and commit are recorded after
the Phase 3 build completes.
