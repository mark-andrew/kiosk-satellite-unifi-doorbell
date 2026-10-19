// SPDX-License-Identifier: Apache-2.0
package net.protectsatellite;

import java.net.*;
import java.util.*;

/** Address-only persistence: never store the session URL, token, port or codec. */
final class TalkbackAddress {
    interface Save { boolean remember(Settings source, String address); }
    static boolean current(Settings source, Settings current) {
        return source.console.equals(current.console) && source.cameraId.equals(current.cameraId) && source.apiKey.equals(current.apiKey)
            && source.certificatePin.equals(current.certificatePin);
    }
    static Map<String, Object> changedSelection(Map<String, Object> incoming, Settings previous) {
        Map<String, Object> next = new HashMap<>(incoming);
        Map<String, Object> endpoint = new HashMap<>(next); endpoint.put("certificatePin", ""); endpoint.put("certificatePinConsole", "");
        Settings candidate = new Settings(endpoint);
        if (!candidate.console.equals(previous.console)) {
            next.put("certificatePin", ""); next.put("certificatePinConsole", ""); next.put("cameraId", ""); next.put("cameraAddress", "");
        } else if (!candidate.cameraId.equals(previous.cameraId)) next.put("cameraAddress", "");
        return next;
    }
    static boolean safe(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isMulticastAddress() || address.isLinkLocalAddress()) return false;
        byte[] bytes = address.getAddress();
        // Reject IPv4 unspecified-network, multicast, reserved and limited broadcast.
        return bytes.length != 4 || ((bytes[0] & 255) != 0 && (bytes[0] & 255) < 224);
    }
}
