# Updating the personal TV APK

The `stproxy` APK uses the repository Actions secret
`SMARTTUBE_PROXY_KEYSTORE_B64` as its permanent signing key. Never commit
the signing key, its Base64 representation, or `keystore.properties` to this
public repository.

1. In the repository's **Settings → Secrets and variables → Actions**, create
   a repository secret named `SMARTTUBE_PROXY_KEYSTORE_B64`. Paste the entire
   contents of `SmartTube-Proxy-signing-secret.txt` into its value, without
   adding whitespace.
2. Keep a private copy of that file. All future builds must use the same
   value; a changed or lost key makes Android refuse an in-place update.
3. Merge code into `main`. The `Build SmartTube Proxy` action signs the APK and
   uploads the ARM64 package. Pull requests compile with temporary debug
   signing and do not expose an installable artifact.

The older GitHub Actions debug APKs used a different, temporary signing key.
Changing to this permanent key requires one uninstall and reinstall. Back up
the old app's subscriptions and preferences before that uninstall. Subsequent
signed builds from this key have the same package name and increasing
`versionCode`, so Android can install them over one another.
