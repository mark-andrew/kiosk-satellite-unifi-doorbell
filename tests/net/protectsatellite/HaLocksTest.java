// SPDX-License-Identifier: Apache-2.0
package net.protectsatellite;

import java.util.*;
import java.util.concurrent.atomic.*;
import me.jxl.kiosk.plugins.PluginHost;

/** Fake host only. No Home Assistant connection or physical lock control. */
final class HaLocksTest {
    static final class Host implements PluginHost {
        final List<Map<String, Object>> sent = new ArrayList<>();
        final List<String> subscriptions = new ArrayList<>(), removed = new ArrayList<>();
        CommandCallback read, call, picker;
        String pickerKey;
        Map<String, Object> pickerMessage;
        public void showWindow(String t, String m, String b) {} public void hideWindow() {} public void log(String s) { throw new AssertionError("No raw HA logs"); }
        public void subscribe(String event) { subscriptions.add(event); }
        public void unsubscribe(String event) { removed.add(event); }
        public void executeCommand(String command, Map<String, Object> args, CommandCallback cb) {
            CoreTest.check(command.equals("getHaEntityState") && args.get("entityId").equals("lock.front"), "Toggle rechecks only the selected lock"); read = cb;
        }
        public void haCall(Map<String, Object> message, CommandCallback cb) { sent.add(message); call = cb; }
        public void haSubscribe(String key, Map<String, Object> message, CommandCallback cb) { pickerKey = key; pickerMessage = message; picker = cb; }
        public void haUnsubscribe(String key) { removed.add(key); }
    }
    static Map<String, Object> snapshot(String status, String state) { return new HashMap<>(Map.of("status", status, "state", state)); }
    static void run() throws Exception {
        CoreTest.check(!HaLocks.valid("switch.front") && !HaLocks.valid("lock.bad/path") && HaLocks.valid("lock.front"), "Only lock IDs are accepted");
        for (String status : new String[] {"unknown", "unavailable", "missing", "connecting", "disconnected", "not_configured", "too_large"})
            CoreTest.check(HaLocks.known(snapshot(status, "locked")).isEmpty(), "Hide nonavailable lock " + status);
        for (String state : new String[] {"unknown", "unavailable", "locking", "unlocking", "jammed", "open", ""})
            CoreTest.check(HaLocks.known(snapshot("available", state)).isEmpty(), "Hide nonconfirmed state " + state);
        CoreTest.check(HaLocks.service("locked").equals("unlock") && HaLocks.service("unlocked").equals("lock") && HaLocks.service("unknown").isEmpty(), "Explicit services toggle only known states");
        Host host = new Host(); AtomicReference<String> state = new AtomicReference<>(""); AtomicBoolean busy = new AtomicBoolean(); List<String> errors = new ArrayList<>();
        HaLocks locks = new HaLocks(host, new HaLocks.Listener() {
            public void changed(String value, boolean waiting) { state.set(value); busy.set(waiting); }
            public void error(String error) { errors.add(error); }
        });
        locks.select(""); locks.toggle(); CoreTest.check(host.sent.isEmpty() && host.subscriptions.isEmpty(), "Optional lock disabled by default");
        locks.select("lock.front"); CoreTest.check(host.subscriptions.equals(Collections.singletonList("ha.entity.lock.front")) && state.get().isEmpty(), "Subscribe without exposing unconfirmed state");
        locks.event("ks.ha.entity.lock.front", snapshot("available", "locked"));
        CoreTest.check(state.get().equals("locked") && host.sent.isEmpty(), "Setup/state feed never operates the lock");
        locks.toggle(); locks.toggle(); CoreTest.check(busy.get() && host.sent.isEmpty(), "First tap waits for fresh state; repeated tap does not send");
        host.read.onResult(true, snapshot("available", "locked"), null);
        CoreTest.check(host.sent.size() == 1 && host.sent.get(0).get("service").equals("unlock") && host.sent.get(0).get("domain").equals("lock")
            && ((Map<?, ?>) host.sent.get(0).get("target")).get("entity_id").equals("lock.front"), "Explicit tap unlocks precisely the selected lock");
        host.call.onResult(true, null, null); locks.toggle();
        CoreTest.check(state.get().equals("locked") && busy.get() && host.sent.size() == 1, "Acknowledgement never optimistically changes color or allows duplicate request");
        locks.event("ks.ha.entity.lock.front", snapshot("available", "unlocked")); locks.toggle(); host.read.onResult(true, snapshot("available", "unlocked"), null);
        CoreTest.check(host.sent.size() == 2 && host.sent.get(1).get("service").equals("lock"), "Confirmed unlocked state tap requests lock");
        host.call.onResult(false, null, "timeout: sensitive details not displayed");
        CoreTest.check(state.get().isEmpty() && !errors.isEmpty() && !errors.get(errors.size() - 1).contains("sensitive"), "Uncertain failure hides state and uses generic error, never auto retries");
        locks.event("ks.ha.entity.lock.front", snapshot("available", "locked")); locks.toggle();
        locks.event("ks.ha.entity.lock.front", snapshot("available", "unlocked")); host.read.onResult(true, snapshot("available", "locked"), null);
        CoreTest.check(host.sent.size() == 2, "State race during preflight sends no service");
        locks.toggle(); locks.cancelAction(); host.read.onResult(true, snapshot("available", "unlocked"), null);
        CoreTest.check(host.sent.size() == 2, "Viewer close cancels a pending preflight before it can operate a lock");
        locks.toggle(); locks.select(""); host.read.onResult(true, snapshot("available", "unlocked"), null);
        locks.event("ks.ha.entity.lock.front", snapshot("available", "locked"));
        CoreTest.check(state.get().isEmpty() && host.sent.size() == 2 && host.removed.contains("ha.entity.lock.front"), "Changing entity revokes late callbacks and old feed");
        AtomicReference<List<HaLocks.Choice>> choices = new AtomicReference<>();
        locks.openPicker((list, error) -> choices.set(list)); String oldKey = host.pickerKey;
        CoreTest.check(host.pickerMessage.get("type").equals("render_template") && host.pickerMessage.get("template").equals(HaLocks.PICKER_TEMPLATE), "Native picker uses a narrow bounded lock-name template, not get_states");
        Map<String, Object> event = Map.of("type", "event", "event", Map.of("result", "[{\"id\":\"lock.front\",\"name\":\"Front\\nDoor\"},{\"id\":\"light.bad\",\"name\":\"No\"},{\"id\":\"lock.front\",\"name\":\"Duplicate\"}]"));
        locks.event("ks.ha.subscription." + oldKey, event);
        CoreTest.check(choices.get().size() == 1 && choices.get().get(0).id.equals("lock.front") && !choices.get().get(0).name.contains("\n"), "Picker filters domains/duplicates and sanitizes names");
        locks.event("ks.ha.subscription." + oldKey, Map.of("type", "event", "event", Map.of("result", Collections.singletonList(Map.of("id", "lock.second", "name", "Second")))));
        CoreTest.check(choices.get().size() == 1 && choices.get().get(0).id.equals("lock.second"), "Picker also accepts HA's parsed template list format");
        locks.closePicker(); choices.set(null); locks.event("ks.ha.subscription." + oldKey, event);
        CoreTest.check(choices.get() == null && host.removed.contains(oldKey), "Closed picker discards late events");
        locks.openPicker((list, error) -> choices.set(list)); locks.event("ks.ha.subscription." + oldKey, event);
        CoreTest.check(choices.get() == null && !host.pickerKey.equals(oldKey), "Reopened picker cannot receive old subscription results");
        locks.stop(); host.picker.onResult(false, null, "gone"); locks.toggle();
        CoreTest.check(host.sent.size() == 2 && choices.get() == null, "Stopped plugin never calls host or delivers pending results");
    }
}
