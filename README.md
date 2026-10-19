# UniFi Protect Doorbell for Kiosk Satellite

An independent Android plugin for live UniFi Protect doorbell video, listening
and tap-to-toggle two-way audio. Connects directly to your Protect console—no
Home Assistant camera integration, go2rtc, FFmpeg or external restreamer needed.
Optional door-lock control uses Kiosk Satellite's existing Home Assistant connection.

**Beta:** device and Protect firmware compatibility varies. Automated tests do
not verify Android playback, microphone shutdown or visual rendering. Test these
on your own device before relying on the plugin.

## Requirements

- Android 8.0/API 26 or newer, with suitable H.264 or H.265 decoding support.
- Stock Kiosk Satellite with plugin SDK 1 and native overlay support.
  **2026.10.31 or newer is recommended** for guided settings on the kiosk and in
  Remote Admin. Earlier compatible hosts retain the native setup screen.
- A UniFi Protect console with the public Integration API and a separate,
  revocable API key permitted to view the doorbell and negotiate Talk sessions.
  Available permission names depend on your Protect version.
- An already enabled RTSPS stream. The plugin never enables streams or changes
  your doorbell's configuration.
- Network access from the satellite to the console's HTTPS and RTSPS ports, and
  to the audio destination returned by Protect for two-way audio.

Doorbells are identified by reported hardware metadata, not their editable name.
The filter recognises G4 Doorbell variants and G6 Entry/Pro Entry model names,
or explicit doorbell metadata. This is **not a claim that every model has been
device-tested**. Ordinary and unidentified cameras are excluded.

## Install and update

### Community Plugins (Recommended)

UniFi Protect Doorbell is available in [Community Plugins](https://kiosksatellite.com/plugins/).

[![Install in Kiosk Satellite](https://kiosksatellite.com/img/badges/plugin-add.svg)](https://kiosksatellite.com/redirect/plugin/?repository=mark-andrew/kiosk-satellite-unifi-doorbell)

Or search for **UniFi Protect Doorbell** under **Settings > Plugin Manager > Browse plugins**.

### Manual repository installation

1. In Kiosk Satellite, open **Settings > Plugin Manager** and turn on **Enable Plugins**.
2. Choose **Add plugin**, paste `https://github.com/mark-andrew/kiosk-satellite-unifi-doorbell`
   and choose **Preview**.
3. Review the source, permissions and README, then choose **Trust and install**.
4. Enable **UniFi Protect Doorbell** and open its settings.

For local developer builds, use **Plugin Manager > Developer Tools > Install from ZIP**.

Update without uninstalling to retain your saved settings. The internal plugin
ID remains `protect-camera`, and its Open command remains `show`, preserving
existing action references. Release filenames therefore remain
`protect-camera-<version>.zip`.

Switching between local ZIP and repository installation may require uninstalling,
which deletes saved settings. Record your preferences securely before switching;
never include your API key in screenshots or issue reports.

## Set up

1. Enter your Protect console's hostname or IP, with `:port` if necessary.
   HTTPS is added automatically. Enter your separate Protect API key.
2. Press **Validate** under **Connect to Protect**. Doorbell, Display, Audio and
   lock settings appear after successful validation.
3. If prompted to trust a self-signed HTTPS certificate, independently verify
   its SHA-256 fingerprint before accepting. A changed saved certificate is
   rejected rather than automatically trusted.
4. Choose your **Doorbell**, then **Refresh** the **Enabled stream** picker.
   Select an available quality. The plugin never silently changes your quality.
5. Run **Open Protect doorbell**. An unconfigured plugin opens native setup;
   the viewer's cog button opens the same setup screen.

Opening the viewer refreshes the doorbell list, stream availability and stream
URL. Existing-doorbell native setup also validates when opened. Changing
connection details invalidates the previous result. Validation never starts
video or the microphone; opening the viewer starts playback, not Talk.

On older hosts without guided settings, use **Open Protect doorbell** to enter
native setup and choose **Save and open doorbell**.

## Viewer controls

- **Close:** stop playback and Talk, then reveal the dashboard. Android Back
  also closes the viewer.
- **Setup:** edit settings. Close or Back returns to the viewer when setup was
  opened from it; Talk does not restart. Unsaved native edits are discarded.
- **Listen/Mute:** turn incoming doorbell audio on or off. Listening starts
  muted by default.
- **Microphone:** enable two-way audio in settings first and grant Kiosk
  Satellite Android microphone permission. Tap once to start Talk and again to
  stop. Green means off; red means starting, live or stopping.
- **Door lock:** optional; see below.

Talk stops on Close, backgrounding, focus loss, stream failure or the microphone
time limit. The default maximum is 20 seconds, including connection time, and
can be set from 5 to 60 seconds. A new session waits for the old one to release
the microphone. Each explicit Talk tap negotiates its current audio destination;
only its validated numeric address is saved internally.

## Display and audio

- **Image sizing:** Stretch is the default. Fit shows the whole picture with
  bars as needed; Fill crops while preserving proportions. Crop-position sliders
  appear only with Fill, and their saved values are retained when hidden.
- **Viewport:** Auto uses the full screen; optional aspect ratios constrain it.
- **Controls:** Top by default, with Bottom, Left and Right also available.
- **Auto-close:** one minute of inactivity by default, with 30 seconds, 2, 5 or
  10 minutes and Off available. Touches and control activation reset the timer.
  Talk suspends it; a fresh interval begins after transmission has stopped.
- **Listening during Talk:** can be muted to reduce feedback.

The plugin requests Android audio focus so other apps may lower or pause their
audio. **This does not duck or mute Kiosk Satellite's built-in music.** It does
not change saved volume levels or pause music groups.

## Optional Home Assistant door lock

Choose a lock in the plugin settings or native setup. Leave it blank to hide
the button. No extra Home Assistant credentials are required.

**A tap operates the selected lock immediately, without confirmation.** Green
means locked; red means unlocked. The button is hidden if state or availability
is unknown. Each tap rechecks state before issuing an explicit lock or unlock
command; uncertain operations are not retried automatically. Locks requiring an
unlock code may reject the command because the plugin does not supply a code.
Setup, opening video and Talk never operate the lock.

## Open when the doorbell rings

In Kiosk Satellite, open the **Open Protect doorbell** action options. Enable
**Show in kiosk drawer** and/or **Expose to Home Assistant**, then Save. These
are host-owned choices and are not enabled by the plugin. Home Assistant exposure
requires Kiosk Satellite's ESPHome/native entities support.

In Home Assistant, create an automation for the real doorbell press event and
call `button.press` on that satellite's exposed plugin Open button. Use the
actual entities from your installation. Add the satellite's supported screen-on
action if needed. The plugin does not create automations or change dashboards.

## Security and limitations

- HTTPS API requests use Android trust and hostname verification, or an explicitly
  approved SHA-256 certificate pin scoped to the console. HTTP is not supported.
- **RTSPS is encrypted but does not verify the stream server's certificate or
  hostname.** This accommodates Protect's separate self-signed streaming
  certificate, but does not protect against server impersonation. Use a trusted
  network and restrict access with firewall rules.
- **UDP talkback is unencrypted.** Its destination is obtained from the authenticated
  Protect response and checked before use. Special addresses such as loopback,
  unspecified, link-local, multicast and limited-broadcast addresses are rejected.
- The Protect key is masked in KS settings; this does not promise hardware-backed
  encrypted storage. Plugins share the host app's trust boundary. Install only
  trusted plugins, and never share credentials or raw stream/session URLs.
- No microphone recordings are written to disk. Protect may finish an in-flight
  session request after cancellation; local capture and transmission stay stopped,
  and the transient server session is left to expire.
- One selected doorbell per satellite. H.264/H.265 video and AAC-LC listening are
  supported within device capabilities. Talk uses negotiated AAC/UDP or Opus/RTP.
  Unsupported codecs or stream formats fail explicitly. There is no transcoding,
  SRTP support or automatic reconnection.
- High-quality streams may exceed the device's decoder capabilities. Software
  fallback may be slow; selecting medium or low quality may be necessary.
- Simultaneous microphone use by KS voice features or other apps can conflict.
  Long-running stability, latency and echo performance require device testing.

## Build and test

Use JDK 17, Python 3.9+, Android SDK platform 35 and Android build-tools with D8.
Set `JAVA_HOME` to your JDK and `ANDROID_HOME` to your Android SDK, with the JDK's
`bin` directory on `PATH`. No Gradle or external runtime libraries are needed.

```sh
python3 tools/test.py
python3 tools/build.py --android-platform 35
```

The build writes `dist/protect-camera-<version>.zip` and a SHA-256 checksum file.
The release contains only the manifest, plugin DEX JAR and license. Tests, SDK
JARs, logs, settings and generated test certificates are excluded.

Tests download a versioned JSON.org JAR and generate test-only certificates under
`build/tests/`. HTTPS tests use a local mock server, not a real Protect console.
They do not use a real microphone or operate a real lock. Offline tests and
source-level UI checks do not replace Android device testing.

Before normal use, verify video, listening, explicit Talk, toggle-off, timeout,
Close/Back, backgrounding and network-failure shutdown on the device. Test the
optional lock deliberately and locally, with a safe way to regain entry.

## License and acknowledgements

Apache-2.0; see [LICENSE](LICENSE). SDK and build-tool provenance is documented in
[sdk/README.md](sdk/README.md). The SDK is compile-time only; stock Kiosk Satellite
supplies the runtime host classes.

This is an independent community project, not an official Ubiquiti or Kiosk
Satellite product. Product names and trademarks belong to their respective owners.
