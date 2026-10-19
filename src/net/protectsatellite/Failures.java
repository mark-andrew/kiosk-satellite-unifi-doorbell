// SPDX-License-Identifier: Apache-2.0
package net.protectsatellite;

final class Failures {
    private Failures() {}
    // Network/decoder exceptions may embed secret stream URLs. Never display raw messages.
    static String describe(Exception e, String fallback) {
        if (e instanceof javax.net.ssl.SSLException) return "TLS verification failed. Check the console certificate or independently verified fingerprint";
        if (e instanceof java.net.SocketTimeoutException) return "Connection timed out; stopped audio and video";
        if (e instanceof java.net.UnknownHostException) return "Configured hostname could not be resolved";
        if (e instanceof SecurityException) return "Microphone access denied. Grant Kiosk Satellite microphone access locally";
        if (e instanceof IllegalArgumentException) {
            String m = e.getMessage();
            if (m != null && (m.startsWith("Console must") || m.startsWith("Set the console") || m.startsWith("Choose a doorbell") || m.startsWith("Certificate fingerprint") || m.startsWith("Out-of-range setting") || m.startsWith("Unsupported setting") || m.startsWith("Invalid camera") || m.startsWith("Camera address") || m.startsWith("Invalid API key"))) return m;
        }
        // Only explicit application-owned IO errors, never messages from network/library code.
        StackTraceElement[] trace = e.getStackTrace();
        if (e.getClass() == java.io.IOException.class && trace.length > 0
                && trace[0].getClassName().matches("net\\.protectsatellite\\.(ProtectApi|SecureTransport|Protocol(?:\\$(?:H264|H265))?|Sdp|StreamPlayer|StreamPlayer\\$Decoder|Talkback|TalkSession|DiscoveredCamera|VideoParameters|VideoParameters\\$Bits|VideoDecoder)")) return e.getMessage();
        return fallback;
    }
}
