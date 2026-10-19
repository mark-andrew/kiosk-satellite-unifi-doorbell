// SPDX-License-Identifier: Apache-2.0
package net.protectsatellite;

import java.net.URI;
import java.util.Map;

final class Settings {
    final URI console;
    final String apiKey, certificatePin, cameraId, cameraAddress, quality, sizing, viewport, controls, lockEntity, autoClose;
    final float horizontal, vertical;
    final int controlSize, talkLimit, autoCloseSeconds;
    final boolean startMuted, muteWhileTalking, talkEnabled;

    Settings(Map<String, Object> m) {
        try {
            String origin = text(m, "console").trim();
            if (!origin.isEmpty() && !origin.contains("://")) {
                if (!origin.startsWith("[") && origin.indexOf(':') != origin.lastIndexOf(':')) origin = "[" + origin + "]";
                origin = "https://" + origin;
            }
            console = URI.create(origin.isEmpty() ? "https://not-configured.invalid" : origin);
            if (!"https".equals(console.getScheme()) || console.getHost() == null
                    || console.getRawUserInfo() != null || console.getRawQuery() != null
                    || console.getRawFragment() != null || console.getPort() == 0 || console.getPort() > 65535
                    || !(console.getPath().isEmpty() || "/".equals(console.getPath())))
                throw new IllegalArgumentException();
        } catch (RuntimeException e) { throw new IllegalArgumentException("Console must be a hostname or IP (optional port), without a path or credentials; HTTPS is required"); }
        apiKey = text(m, "apiKey");
        if (apiKey.contains("\r") || apiKey.contains("\n")) throw new IllegalArgumentException("Invalid API key format");
        certificatePin = text(m, "certificatePin").replace(":", "").replace(" ", "").toLowerCase(java.util.Locale.ROOT);
        if (!certificatePin.isEmpty() && !certificatePin.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Certificate fingerprint must contain 64 hexadecimal digits");
        String pinConsole = text(m, "certificatePinConsole");
        if (!certificatePin.isEmpty() && !pinConsole.isEmpty() && !pinConsole.equals(console.toString()))
            throw new IllegalArgumentException("Certificate fingerprint belongs to a different console; validate the connection again");
        cameraId = text(m, "cameraId").trim();
        if (!cameraId.isEmpty() && !cameraId.matches("[A-Za-z0-9_-]{1,128}"))
            throw new IllegalArgumentException("Invalid camera ID format");
        cameraAddress = text(m, "cameraAddress").trim();
        if (!cameraAddress.isEmpty() && !cameraAddress.matches("[A-Za-z0-9.:-]+"))
            throw new IllegalArgumentException("Camera address must be an IP or hostname only");
        quality = choice(m, "quality", "medium", "low", "medium", "high");
        sizing = choice(m, "sizing", "Stretch", "Fit", "Fill", "Stretch");
        viewport = choice(m, "viewport", "Auto", "Auto", "4:3", "16:9", "16:10");
        controls = choice(m, "controls", "Top", "Top", "Bottom", "Left", "Right");
        autoClose = choice(m, "autoClose", "1 minute", "Off", "30 seconds", "1 minute", "2 minutes", "5 minutes", "10 minutes");
        autoCloseSeconds = "Off".equals(autoClose) ? 0 : "30 seconds".equals(autoClose) ? 30 : "1 minute".equals(autoClose) ? 60
            : "2 minutes".equals(autoClose) ? 120 : "5 minutes".equals(autoClose) ? 300 : 600;
        lockEntity = text(m, "lockEntity").trim();
        if (!lockEntity.isEmpty() && (lockEntity.length() > 255 || !lockEntity.matches("lock\\.[a-z0-9_]+")))
            throw new IllegalArgumentException("Choose a lock entity or leave Door lock empty");
        horizontal = number(m, "horizontal", 50, 0, 100) / 100f;
        vertical = number(m, "vertical", 50, 0, 100) / 100f;
        controlSize = number(m, "controlSize", 56, 48, 96);
        talkLimit = number(m, "talkLimit", 20, 5, 60);
        startMuted = flag(m, "startMuted", true);
        muteWhileTalking = flag(m, "muteWhileTalking", true);
        talkEnabled = flag(m, "talkEnabled", false);
    }
    void requireConnection() {
        if (console.getHost().endsWith(".invalid") || apiKey.isEmpty())
            throw new IllegalArgumentException("Set the console hostname and API key in plugin settings");
    }
    void requireCamera() {
        requireConnection();
        if (cameraId.isEmpty()) throw new IllegalArgumentException("Choose a doorbell in setup before opening the viewer");
    }
    private static String text(Map<String, Object> m, String key) { Object v = m.get(key); return v instanceof String ? (String) v : ""; }
    private static boolean flag(Map<String, Object> m, String key, boolean d) { return m.get(key) instanceof Boolean ? (Boolean) m.get(key) : d; }
    private static int number(Map<String, Object> m, String key, int d, int min, int max) {
        Object v = m.get(key); double n = v instanceof Number ? ((Number) v).doubleValue() : d;
        if (!Double.isFinite(n) || n < min || n > max) throw new IllegalArgumentException("Out-of-range setting: " + key);
        return (int) Math.round(n);
    }
    private static String choice(Map<String, Object> m, String key, String d, String... options) {
        String v = text(m, key); if (v.isEmpty()) return d;
        for (String o : options) if (o.equals(v)) return v;
        throw new IllegalArgumentException("Unsupported setting: " + key);
    }
}
