// SPDX-License-Identifier: Apache-2.0
package net.protectsatellite;

import java.net.*;
import java.io.*;
import java.util.*;
import org.json.JSONObject;

final class AddressRoutingTest {
    static void run() throws Exception {
        TalkSession session = new TalkSession(new JSONObject().put("codec", "opus").put("samplingRate", 24000).put("url", "rtp://camera.example:5000"));
        InetAddress camera = InetAddress.getByAddress(new byte[] {10, 0, 1, 20});
        InetAddress learned = session.authenticatedDestination(host -> new InetAddress[] {camera});
        CoreTest.check(learned.equals(camera) && learned.getHostAddress().equals("10.0.1.20"), "Authenticated session destination can be learned without a prior manual camera address");
        for (String bad : new String[] {"0.0.0.0", "0.1.2.3", "127.0.0.1", "169.254.169.254", "224.0.0.1", "255.255.255.255", "240.0.0.1", "::", "::1", "ff02::1", "fe80::1"}) {
            InetAddress address = InetAddress.getByName(bad);
            CoreTest.rejects(() -> session.authenticatedDestination(host -> new InetAddress[] {address}), "unsafe authenticated destination " + bad);
        }
        CoreTest.rejects(() -> session.authenticatedDestination(host -> new InetAddress[0]), "empty DNS response");
        CoreTest.rejects(() -> session.authenticatedDestination(host -> new InetAddress[] {camera, InetAddress.getLoopbackAddress()}), "mixed safe/unsafe DNS response");
        Map<String, Object> values = new HashMap<>(); values.put("console", "console.example"); values.put("apiKey", "test-key");
        values.put("cameraId", "cam1"); values.put("cameraAddress", "10.0.1.20"); values.put("vertical", 65); values.put("talkLimit", 35);
        Settings original = new Settings(values);
        Map<String, Object> same = TalkbackAddress.changedSelection(values, original);
        CoreTest.check(same.get("cameraAddress").equals("10.0.1.20"), "Same console/camera retains learned destination");
        Map<String, Object> edited = new HashMap<>(values); edited.put("cameraId", "cam2");
        Map<String, Object> next = TalkbackAddress.changedSelection(edited, original);
        CoreTest.check(next.get("cameraAddress").equals("") && next.get("cameraId").equals("cam2"), "Manual settings camera change clears saved destination");
        CoreTest.check(next.get("vertical").equals(65) && next.get("talkLimit").equals(35) && next.get("apiKey").equals("test-key"), "Clearing learned address preserves unrelated settings and secret");
        edited.put("console", "other-console.example"); edited.put("certificatePin", "a".repeat(64));
        next = TalkbackAddress.changedSelection(edited, original);
        CoreTest.check(next.get("cameraAddress").equals("") && next.get("cameraId").equals("") && next.get("certificatePin").equals(""), "Console change clears address, camera selection and certificate approval");
        CoreTest.check(TalkbackAddress.current(original, new Settings(values)), "Current camera response eligible to save");
        edited = new HashMap<>(values); edited.put("cameraId", "cam2");
        CoreTest.check(!TalkbackAddress.current(original, new Settings(edited)), "Old-camera result cannot save into a new camera");
        edited = new HashMap<>(values); edited.put("apiKey", "replacement-key");
        CoreTest.check(!TalkbackAddress.current(original, new Settings(edited)), "Old-credential result cannot save into changed settings");
        CoreTest.check(FailureRouting.setup(new IOException("auth error"), true), "Opening/authentication failures route to setup");
        CoreTest.check(FailureRouting.setup(new SocketTimeoutException(), false) && FailureRouting.setup(new EOFException(), false), "Network failure after opening routes to setup");
        CoreTest.check(!FailureRouting.setup(new IllegalStateException("decoder"), false) && !FailureRouting.setup(new IOException("unsupported video"), false), "Decoder/media errors stay in viewer, not setup loops");
    }
}
