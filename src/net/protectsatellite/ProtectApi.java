// SPDX-License-Identifier: Apache-2.0
package net.protectsatellite;

import java.io.*;
import java.net.URI;
import java.net.HttpURLConnection;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.HttpsURLConnection;
import org.json.*;

final class ProtectApi implements AutoCloseable {
    private static final int MAX_RESPONSE = 1024 * 1024;
    final Settings settings;
    final SecureTransport tls;
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile NetworkCleanup.Task active;
    ProtectApi(Settings settings) throws Exception { settings.requireConnection(); this.settings = settings; tls = new SecureTransport(settings); }
    private synchronized Object request(String method, String path) throws Exception {
        if (closed.get()) throw new IOException("Connection check cancelled");
        URI uri = settings.console.resolve("/proxy/protect/integration/v1/" + path);
        HttpsURLConnection c = (HttpsURLConnection) uri.toURL().openConnection();
        NetworkCleanup.Task cleanup = new NetworkCleanup.Task(c::disconnect);
        active = cleanup;
        try {
            tls.configure(c); c.setInstanceFollowRedirects(false); c.setConnectTimeout(8000); c.setReadTimeout(8000);
            c.setRequestMethod(method); c.setRequestProperty("X-API-KEY", settings.apiKey); c.setRequestProperty("Accept", "application/json");
            if (closed.get()) throw new IOException("Request cancelled");
            int code = c.getResponseCode();
            if (code == 401 || code == 403) throw new IOException("Protect rejected the API key or its permissions (HTTP " + code + ")");
            if (code < 200 || code >= 300) throw new IOException("Protect API returned HTTP " + code + "; verify API version and permissions");
            try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] b = new byte[4096]; int n;
                while ((n = in.read(b)) != -1) { if (closed.get()) throw new IOException("Request cancelled"); if (out.size() + n > MAX_RESPONSE) throw new IOException("Protect response exceeds safety limit"); out.write(b, 0, n); }
                try { return new JSONTokener(out.toString("UTF-8")).nextValue(); }
                catch (JSONException e) { throw new IOException("Protect returned an unsupported JSON response"); }
            }
        } finally {
            // This method runs on the API worker, never on the Android UI thread.
            cleanup.closeOnOwnerThread(); active = null;
        }
    }
    JSONArray cameras() throws Exception {
        Object result = request("GET", "cameras");
        if (!(result instanceof JSONArray)) throw new IOException("Expected a camera list from Protect");
        return (JSONArray) result;
    }
    JSONObject camera() throws Exception { return object(request("GET", "cameras/" + settings.cameraId)); }
    URI stream() throws Exception {
        JSONObject streams = streams(settings.cameraId);
        return streamUrl(streams, settings);
    }
    JSONObject streams(String cameraId) throws Exception {
        if (!cameraId.matches("[A-Za-z0-9_-]{1,128}")) throw new IOException("Invalid camera ID for stream discovery");
        return object(request("GET", "cameras/" + cameraId + "/rtsps-stream"));
    }
    static URI streamUrl(JSONObject streams, Settings settings) throws IOException {
        Object raw = streams.opt(settings.quality);
        String prefix = "Selected " + settings.quality + " stream: ";
        if (raw == null || raw == JSONObject.NULL)
            throw new IOException(prefix + "unavailable. Enable it in Protect or choose another quality.");
        if (!(raw instanceof String)) {
            // Never coerce JSON objects/arrays into URLs: their contents may include tokens.
            throw new IOException(prefix + "Protect returned an unsupported stream value.");
        }
        String value = (String) raw;
        if (value.isEmpty()) throw new IOException(prefix + "unavailable. Enable it in Protect or choose another quality.");
        if (value.endsWith("?enableSrtp")) value = value.substring(0, value.length() - 11);
        URI u;
        try {
            u = URI.create(value);
        } catch (RuntimeException e) {
            // URI parser exception messages can contain the complete secret URL.
            throw new IOException(prefix + "Protect returned an invalid stream URL.");
        }
        if (!"rtsps".equals(u.getScheme())) throw new IOException(prefix + "RTSPS is required.");
        if (u.getHost() == null) throw new IOException(prefix + "stream host is missing or invalid.");
        if (u.getUserInfo() != null) throw new IOException(prefix + "URLs containing credentials are not supported.");
        if (u.getFragment() != null) throw new IOException(prefix + "URL fragments are not supported.");
        try {
            // Multi-VLAN consoles can return a different interface. Keep token and port,
            // but use the explicitly configured console host for reachability and TLS identity.
            return new URI(u.getScheme(), null, settings.console.getHost(), u.getPort(), u.getPath(), u.getQuery(), null);
        } catch (java.net.URISyntaxException | RuntimeException e) {
            throw new IOException(prefix + "stream URL could not be prepared.");
        }
    }
    JSONObject talkback() throws Exception { return object(request("POST", "cameras/" + settings.cameraId + "/talkback-session")); }
    private static JSONObject object(Object value) throws IOException {
        if (!(value instanceof JSONObject)) throw new IOException("Expected a Protect response object"); return (JSONObject) value;
    }
    public void close() { closed.set(true); NetworkCleanup.Task c = active; if (c != null) c.close(); }
}
