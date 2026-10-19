// SPDX-License-Identifier: Apache-2.0
package net.protectsatellite;

import java.util.*;
import me.jxl.kiosk.plugins.PluginHost;
import org.json.*;

/** Optional HA lock support. KS owns credentials; no Protect credential is reused. */
final class HaLocks {
    interface Listener { void changed(String state, boolean busy); void error(String message); }
    interface Picker { void result(List<Choice> locks, String error); }
    static final class Choice {
        final String id, name;
        Choice(String id, String name) { this.id = id; this.name = name; }
        public String toString() { return name.equals(id) ? id : name + " — " + id; }
    }
    private PluginHost host;
    private final Listener listener;
    private String entity = "", state = "";
    private int revision, pickerRevision;
    private long eventRevision;
    private boolean subscribed, busy;
    private Picker picker;
    private String pickerKey = "";
    private static final String PICKER = "protect_lock_picker";
    static final String PICKER_TEMPLATE = "{% set n = namespace(rows=[]) %}{% for e in (states.lock | sort(attribute='entity_id') | list)[:64] %}"
        + "{% set n.rows = n.rows + [{'id': e.entity_id, 'name': e.name[:80]}] %}{% endfor %}{{ n.rows | to_json }}";
    HaLocks(PluginHost host, Listener listener) { this.host = host; this.listener = listener; }
    static boolean valid(String id) { return id != null && id.length() <= 255 && id.matches("lock\\.[a-z0-9_]+"); }
    static String known(Object snapshot) {
        if (!(snapshot instanceof Map)) return "";
        Map<?, ?> m = (Map<?, ?>) snapshot;
        if (!"available".equals(m.get("status"))) return "";
        return "locked".equals(m.get("state")) ? "locked" : "unlocked".equals(m.get("state")) ? "unlocked" : "";
    }
    static String service(String state) { return "locked".equals(state) ? "unlock" : "unlocked".equals(state) ? "lock" : ""; }
    synchronized void select(String id) {
        revision++; eventRevision++; busy = false; state = "";
        if (host != null && subscribed) try { host.unsubscribe("ha.entity." + entity); } catch (RuntimeException ignored) {}
        subscribed = false; entity = valid(id) ? id : ""; publish();
        if (host != null && !entity.isEmpty()) try { host.subscribe("ha.entity." + entity); subscribed = true; }
        catch (RuntimeException ignored) { listener.error("HA lock status is unavailable on this kiosk"); }
    }
    private void publish() { listener.changed(state, busy); }
    synchronized void refresh() { publish(); }
    synchronized void event(String name, Map<String, Object> payload) {
        if (host == null) return;
        if (!entity.isEmpty() && name.equals("ks.ha.entity." + entity)) {
            String next = known(payload); eventRevision++;
            if (!next.equals(state) || next.isEmpty()) busy = false;
            state = next; publish();
        }
        if (name.equals("ks.ha.subscription." + pickerKey) && picker != null) {
            if ("event".equals(payload.get("type"))) {
                Object event = payload.get("event");
                try {
                    Object result = event instanceof Map ? ((Map<?, ?>) event).get("result") : null;
                    JSONArray rows;
                    if (result instanceof String) rows = new JSONArray((String) result);
                    else if (result instanceof Collection) rows = new JSONArray((Collection<?>) result);
                    else throw new IllegalArgumentException("Unsupported lock list");
                    List<Choice> choices = new ArrayList<>(); Set<String> ids = new HashSet<>();
                    for (int i = 0; i < Math.min(rows.length(), 64); i++) {
                        JSONObject row = rows.getJSONObject(i); String id = row.optString("id", "");
                        if (valid(id) && ids.add(id)) choices.add(new Choice(id, DisplayText.singleLine(row.optString("name", id), 80)));
                    }
                    picker.result(Collections.unmodifiableList(choices), "");
                } catch (Exception ignored) { picker.result(Collections.emptyList(), "Could not read HA lock list"); }
            } else if (!"resubscribed".equals(payload.get("type"))) picker.result(Collections.emptyList(), "HA lock picker disconnected or unavailable");
        }
    }
    synchronized void openPicker(Picker callback) {
        closePicker(); picker = callback;
        final int token = pickerRevision;
        pickerKey = PICKER + "_" + token;
        if (host == null) { callback.result(Collections.emptyList(), "Home Assistant is unavailable"); return; }
        try {
            Map<String, Object> message = new HashMap<>(); message.put("type", "render_template"); message.put("template", PICKER_TEMPLATE);
            host.haSubscribe(pickerKey, message, (ok, data, error) -> {
                synchronized (HaLocks.this) { if (!ok && host != null && token == pickerRevision && picker != null)
                    picker.result(Collections.emptyList(), "Cannot load locks — check this kiosk's HA connection and permissions"); }
            });
        } catch (RuntimeException ignored) { callback.result(Collections.emptyList(), "HA lock picker is not supported by this kiosk"); }
    }
    synchronized void closePicker() {
        pickerRevision++; Picker old = picker; picker = null; String key = pickerKey; pickerKey = "";
        if (old != null && host != null) try { host.haUnsubscribe(key); } catch (RuntimeException ignored) {}
    }
    synchronized void cancelAction() { revision++; busy = false; publish(); }
    synchronized void toggle() {
        if (host == null || entity.isEmpty() || state.isEmpty() || busy) return;
        busy = true; publish(); final int token = revision; final String id = entity, expected = state;
        final long before = eventRevision;
        try {
            host.executeCommand("getHaEntityState", Collections.singletonMap("entityId", id), (ok, data, error) -> {
                synchronized (HaLocks.this) {
                    if (host == null || token != revision) return;
                    // Do not invert a newly changed lock or trust an old snapshot.
                    if (!ok || !expected.equals(known(data)) || before != eventRevision || !expected.equals(state)) {
                        busy = false; if (!ok || known(data).isEmpty()) state = ""; publish();
                        listener.error("Lock status changed or is unavailable; no command sent"); return;
                    }
                    Map<String, Object> message = new HashMap<>(); message.put("type", "call_service"); message.put("domain", "lock"); message.put("service", service(expected));
                    message.put("target", Collections.singletonMap("entity_id", id));
                    try { host.haCall(message, (sent, result, failure) -> {
                        synchronized (HaLocks.this) {
                            if (host == null || token != revision) return;
                            if (!sent) { busy = false; state = ""; publish(); listener.error("Lock command failed or outcome is unconfirmed; check Home Assistant. Not retried."); }
                            // Success is not a confirmed lock state: wait for the subscription.
                        }
                    }); } catch (RuntimeException ignored) { fail("Lock control is unavailable; check Home Assistant"); }
                }
            });
        } catch (RuntimeException ignored) { fail("Cannot verify lock status; no command sent"); }
    }
    private void fail(String message) { busy = false; state = ""; publish(); listener.error(message); }
    // Host access is already revoked at plugin stop.
    synchronized void stop() { revision++; pickerRevision++; picker = null; host = null; state = ""; busy = false; }
}
