// SPDX-License-Identifier: Apache-2.0
// Modified from the Community SDK baseline to include the public SDK 1 setup
// methods released in Kiosk Satellite 2026.10.31. See sdk/README.md.
package me.jxl.kiosk.plugins;

import java.util.Map;

/** Versioned host API. Access is scoped to the plugin and revoked when it stops. */
public interface PluginHost {
    /** SDK 1. Detached JSON-compatible data, or null. Errors have ok=false. */
    interface CommandCallback {
        void onResult(boolean ok, Object data, String error);
    }

    /** SDK 1, host.read or host.control. Asynchronous documented KS commands. */
    default void executeCommand(String command, Map<String, Object> arguments, CommandCallback callback) { throw new UnsupportedOperationException("SDK 1 required"); }
    /** SDK 1, host.read. Events arrive through onEvent("ks." + event, payload). */
    default void subscribe(String event) { throw new UnsupportedOperationException("SDK 1 required"); }
    /** SDK 1. Stop observing an event. All subscriptions end with the session. */
    default void unsubscribe(String event) { throw new UnsupportedOperationException("SDK 1 required"); }
    /**
     * SDK 1, ha. Send one Home Assistant websocket command without its id, such as {type: "call_service", domain,
     * service, service_data, target}. KS signs in with its own token and numbers the message. data is HA's result. On
     * failure, error reads "code: message" and data holds {code, message}. Credential commands are refused.
     */
    default void haCall(Map<String, Object> message, CommandCallback callback) { throw new UnsupportedOperationException("Home Assistant access is unavailable"); }
    /**
     * SDK 1, ha. Start a Home Assistant subscription, such as subscribe_trigger or render_template, under a key of the
     * plugin's choosing. The callback says whether HA accepted it. Events arrive through
     * onEvent("ks.ha.subscription." + key, payload) until haUnsubscribe or the end of the session. At most 16.
     */
    default void haSubscribe(String key, Map<String, Object> message, CommandCallback callback) { throw new UnsupportedOperationException("Home Assistant access is unavailable"); }
    /** SDK 1, ha. End a subscription. Unknown keys are ignored. */
    default void haUnsubscribe(String key) { throw new UnsupportedOperationException("Home Assistant access is unavailable"); }
    /** SDK 1, shizuku. Current availability, permission, backend UID and version. Does not prompt. */
    default Map<String, Object> shizukuState() { throw new UnsupportedOperationException("Shizuku is unavailable"); }
    /** SDK 1, shizuku. Run an absolute executable with separate arguments after the user grants KS access. */
    default void executeShizuku(String[] command, int timeoutMs, CommandCallback callback) { throw new UnsupportedOperationException("Shizuku is unavailable"); }
    /** SDK 1, screensaver. Register self-contained HTML as a stock screensaver mode. Does not activate it. */
    default void publishScreensaver(String key, String title, String html) { throw new UnsupportedOperationException("Screensavers are unavailable"); }
    /** SDK 1, screensaver. Register an HTML path relative to assets/ with scalar JSON data. Assets load on demand. */
    default void publishScreensaverAsset(String key, String title, String entry, Map<String, Object> data) { throw new UnsupportedOperationException("Screensaver assets are unavailable"); }
    /** SDK 1, screensaver. Remove a renderer. KS retains the session with a black background. */
    default void removeScreensaver(String key) { throw new UnsupportedOperationException("Screensavers are unavailable"); }
    /** Publish a bounded chart snapshot. SDK 1. */
    default void publishSeries(String key, Map<String, Object> chart) { throw new UnsupportedOperationException("Charts are unavailable"); }
    /** Remove this session's chart with the given key. */
    default void removeSeries(String key) { throw new UnsupportedOperationException("Charts are unavailable"); }
    /** SDK 1. Publish or update a tile on the Remote Admin Overview Status panel. Level is "on", "warn", "off" or "". */
    default void publishStatusTile(String key, String title, String level, String text) { throw new UnsupportedOperationException("Status tiles are unavailable"); }
    /** Remove this session's status tile with the given key. */
    default void removeStatusTile(String key) { throw new UnsupportedOperationException("Status tiles are unavailable"); }
    /** SDK 1. Fire a trigger declared in the manifest. Gestures mapped to it run their actions. At most four per second, extras are dropped. */
    default void fireTrigger(String id) { throw new UnsupportedOperationException("Gesture triggers are unavailable"); }
    /** SDK 1, overlay. Show a native overlay, or replace the one with this key. Never blocks: KS builds its view later on the main thread through the factory. At most four per plugin. */
    default void showOverlay(String key, OverlaySpec spec, OverlayFactory factory) { throw new UnsupportedOperationException("Native overlays are unavailable"); }
    /** SDK 1, overlay. Remove an overlay. Unknown keys are ignored. */
    default void hideOverlay(String key) { throw new UnsupportedOperationException("Native overlays are unavailable"); }
    /**
     * SDK 1, overlay. Keep the dashboard clear of a bar: Home Assistant pads its views, dialogs and toasts by these
     * distances from each edge, 0 to 4096 dp, so nothing hides under the bar. All zero clears it, and so does the end
     * of the session. With several plugins, each edge keeps the largest.
     */
    default void setContentInsets(int left, int top, int right, int bottom) { throw new UnsupportedOperationException("Content insets are unavailable"); }
    /** True on a device without a screen, where overlays, windows and screensavers are never shown. A plugin that declares "headless": true checks this and skips them. */
    default boolean isHeadless() { return false; }
    /** Show or update this plugin's one floating window. Text is plain text. */
    void showWindow(String title, String message, String buttonLabel);
    void hideWindow();
    void log(String message);
    /** SDK 1. Absolute path to a verified library private to this session. */
    default String nativeLibraryPath(String name) { throw new UnsupportedOperationException("SDK 1 required"); }
    /** SDK 1. Verified DEX container, for a plugin-owned helper process. */
    default String packagePath() { throw new UnsupportedOperationException("SDK 1 required"); }
    /** SDK 1. Absolute path to a verified, read-only file under the package's assets folder, such as a font for Typeface.createFromFile. */
    default String assetPath(String name) { throw new UnsupportedOperationException("Plugin assets are unavailable"); }
    /** SDK 1. Open a verified file under the package's assets folder for reading. The caller closes the stream. */
    default java.io.InputStream openAsset(String name) throws java.io.IOException { throw new UnsupportedOperationException("Plugin assets are unavailable"); }
    /** SDK 1. Display a runtime status in the plugin subpage. */
    default void status(String message, boolean error) { throw new UnsupportedOperationException("SDK 1 required"); }
    /** SDK 1. Persist settings changed by plugin actions or Home Assistant. */
    default void saveSettings(Map<String, Object> values) { throw new UnsupportedOperationException("SDK 1 required"); }
    /** SDK 1 setup controls (KS 2026.10.30+). Save only named settings; omitted secrets stay saved. */
    default void updateSettings(Map<String, Object> values) { throw new UnsupportedOperationException("Setup controls are unavailable"); }
    /** Report running/success/error for a host-owned setup run. False once the run has ended. */
    default boolean setupResult(String run, String state, String message) { throw new UnsupportedOperationException("Setup controls are unavailable"); }
    /** Confirm in the interface that started the run. Answer arrives as settings.confirm. */
    default boolean confirmSetup(String run, String title, String message, String confirmLabel) { throw new UnsupportedOperationException("Setup controls are unavailable"); }
    /** Replace choices for a top-level string setting declared with choices=plugin. */
    default void setSettingChoices(String key, String[] ids, String[] labels) { throw new UnsupportedOperationException("Setup controls are unavailable"); }
    /** SDK 1. Register or update an RGB light. Commands arrive as light.<key>. */
    default void publishLight(String key, String name, String[] effects, Map<String, Object> state) { throw new UnsupportedOperationException("SDK 1 required"); }
    default void removeLight(String key) { throw new UnsupportedOperationException("SDK 1 required"); }
    /** SDK 1, entities. Numeric state or null for unknown. Metadata: unit, deviceClass, stateClass, accuracyDecimals. */
    default void publishSensor(String key, String name, Map<String, Object> metadata, Double state) { throw new UnsupportedOperationException("SDK 1 required"); }
    default void removeSensor(String key) { throw new UnsupportedOperationException("SDK 1 required"); }
    /** SDK 1, entities. Text state (up to 512 characters) or null for unknown. */
    default void publishTextSensor(String key, String name, String state) { throw new UnsupportedOperationException("SDK 1 required"); }
    default void removeTextSensor(String key) { throw new UnsupportedOperationException("SDK 1 required"); }
    /** SDK 1, entities. Boolean state or null for unknown. Empty deviceClass means none. */
    default void publishBinarySensor(String key, String name, String deviceClass, Boolean state) { throw new UnsupportedOperationException("SDK 1 required"); }
    default void removeBinarySensor(String key) { throw new UnsupportedOperationException("SDK 1 required"); }
    /** SDK 1, entities. Confirmed boolean state. Commands arrive as switch.KEY with {on: boolean}. */
    default void publishSwitch(String key, String name, boolean state) { throw new UnsupportedOperationException("SDK 1 required"); }
    default void removeSwitch(String key) { throw new UnsupportedOperationException("SDK 1 required"); }
    /** SDK 1, entities. Changes arrive as select.KEY with {option: string}. Publish the applied option to confirm it. */
    default void publishSelect(String key, String name, String[] options, String state) { throw new UnsupportedOperationException("SDK 1 required"); }
    default void removeSelect(String key) { throw new UnsupportedOperationException("SDK 1 required"); }
}
