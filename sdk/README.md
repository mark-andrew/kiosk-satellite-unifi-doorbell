# Compile-time SDK and upstream attribution

The Java files under `sdk/src/` are compile-time interfaces from the
[Kiosk Satellite Community SDK](https://github.com/jxlarrea/kiosk-satellite-plugin-hello-world),
baseline commit `233e7391ac6e2efd4d271b28dc588c88a956b7c5`.
They are licensed under Apache-2.0; see the root `LICENSE`.

`PluginHost.java` is modified to include the documented SDK 1 setup methods
`updateSettings`, `setupResult`, `confirmSetup` and `setSettingChoices`, available
in stock Kiosk Satellite 2026.10.31. These additions do not modify the host app.
See the upstream [setup contract](https://github.com/jxlarrea/kiosk-satellite-plugin-hello-world/blob/main/docs/setup-controls.md).
The other four SDK source files are unchanged from the baseline.

The Python build utilities `tools/build.py`, `tools/android_sdk.py`,
`tools/plugin_assets.py` and `tools/plugin_manifest.py` are also unchanged from
that Community SDK baseline. `tools/test.py` is adapted for this plugin's tests.

The release ZIP contains the plugin's DEX, manifest and Apache-2.0 license.
The SDK JAR, SDK implementations, build utilities and automated tests are not
bundled. Kiosk Satellite supplies the host classes at runtime.

The test runner downloads JSON.org `org.json:json:20250517` from Maven Central
for JVM tests only. Android supplies `org.json` at runtime; the downloaded JAR
is not part of a plugin release.

This is an independent community plugin. Kiosk Satellite, UniFi and Ubiquiti
remain the names and trademarks of their respective owners. This project does
not imply endorsement by them.
