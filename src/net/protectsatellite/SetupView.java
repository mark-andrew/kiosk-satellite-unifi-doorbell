// SPDX-License-Identifier: Apache-2.0
package net.protectsatellite;

import android.app.*;
import android.content.Context;
import android.os.Bundle;
import android.text.*;
import android.view.*;
import android.widget.*;
import java.util.*;
import me.jxl.kiosk.plugins.KsTheme;

/** Stock-SDK native setup renderer. Shared workflow has no Android dependency. */
final class SetupView extends FrameLayout implements Application.ActivityLifecycleCallbacks {
    private final SetupController controller;
    private final Runnable closeAction;
    private final Runnable savedAction;
    private final Runnable backgroundAction;
    private final HaLocks locks;
    private final Map<String, Object> preferences = new HashMap<>();
    private final List<CheckBox> checks = new ArrayList<>();
    private final List<Spinner> selectors = new ArrayList<>();
    private KsTheme theme;
    private final List<TextView> labels = new ArrayList<>();
    private final List<EditText> inputs = new ArrayList<>();
    private final List<Button> buttons = new ArrayList<>();
    private final EditText console, key;
    private final List<View> gated = new ArrayList<>();
    private LinearLayout crop;
    private final TextView status, certificate;
    private final Button validate, accept, save;
    private final Spinner cameras, streams;
    private final Application application;
    private List<DiscoveredCamera> choices = Collections.emptyList();
    private List<String> qualities = Collections.emptyList();
    private TextView initialProblem;
    private boolean updatingStreams;
    private boolean disposed;
    private boolean gatingShown;
    SetupView(Context context, KsTheme theme, Map<String, Object> values, String reason, SetupController.Save apply, Runnable closeAction, Runnable savedAction, Runnable backgroundAction, HaLocks locks) {
        super(context); this.theme = theme; this.closeAction = closeAction; this.savedAction = savedAction; this.backgroundAction = backgroundAction; this.locks = locks;
        Settings initial = new Settings(values);
        setBackgroundColor(theme.color("surface")); setClickable(true);
        ScrollView scroll = new ScrollView(context); addView(scroll, new LayoutParams(-1, -1));
        LinearLayout content = new LinearLayout(context); content.setOrientation(LinearLayout.VERTICAL); content.setPadding(dp(24), dp(12), dp(24), dp(24)); scroll.addView(content);
        LinearLayout header = new LinearLayout(context);
        TextView title = text("Set up Protect doorbell", 24); header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        Button back = button("Close"); back.setOnClickListener(v -> { dispose(); closeAction.run(); }); header.addView(back); content.addView(header);
        content.addView(text("Connect to Protect and choose your doorbell. Setup does not start video or the microphone.", 15));
        if (!reason.isEmpty()) { initialProblem = text(reason, 16); initialProblem.setTextColor(theme.color("error")); content.addView(initialProblem); }
        LinearLayout connection = section(content, "Connection / Doorbell", initial.cameraId.isEmpty());
        connection.addView(text("Validate the connection to choose a UniFi doorbell and its enabled stream.", 15));
        console = field(connection, "Console hostname or IP (optional port)", false);
        String origin = Objects.toString(values.get("console"), ""); console.setText(origin.startsWith("https://") ? origin.substring(8) : origin);
        console.setSingleLine(true);
        key = field(connection, "Protect API key", true); key.setText(Objects.toString(values.get("apiKey"), ""));
        validate = button("Validate connection"); connection.addView(validate);
        status = text("", 16); content.addView(status);
        certificate = text("", 14); certificate.setTextIsSelectable(true); certificate.setVisibility(GONE); connection.addView(certificate);
        accept = button("Accept this certificate and validate"); accept.setVisibility(GONE); connection.addView(accept);
        LinearLayout selection = new LinearLayout(context); selection.setOrientation(LinearLayout.VERTICAL); connection.addView(selection); gated.add(selection);
        selection.addView(text("Doorbell", 17)); cameras = new Spinner(context); selection.addView(cameras);
        selection.addView(text("Enabled stream", 17)); streams = new Spinner(context); selection.addView(streams);
        LinearLayout display = section(content, "Display", true);
        preference(display, "Image sizing", "sizing", initial.sizing, "Fit", "Fill", "Stretch");
        preference(display, "Viewport aspect", "viewport", initial.viewport, "Auto", "4:3", "16:9", "16:10");
        preference(display, "Control placement", "controls", initial.controls, "Top", "Bottom", "Left", "Right");
        preference(display, "Auto-close after inactivity", "autoClose", initial.autoClose, "Off", "30 seconds", "1 minute", "2 minutes", "5 minutes", "10 minutes");
        display.addView(text("Controls reset the timer. Talk holds it; a fresh timeout starts once transmission has stopped.", 14));
        crop = new LinearLayout(context); crop.setOrientation(LinearLayout.VERTICAL); display.addView(crop);
        slider(crop, "Horizontal crop position", "horizontal", Math.round(initial.horizontal * 100), 0, 100, 1);
        slider(crop, "Vertical crop position", "vertical", Math.round(initial.vertical * 100), 0, 100, 1);
        updateCrop();
        slider(display, "Control size (dp)", "controlSize", initial.controlSize, 48, 96, 4);
        LinearLayout audio = section(content, "Audio", true);
        flag(audio, "Start with listening muted", "startMuted", initial.startMuted);
        flag(audio, "Mute listening while talking", "muteWhileTalking", initial.muteWhileTalking);
        flag(audio, "Enable two-way audio", "talkEnabled", initial.talkEnabled);
        slider(audio, "Microphone auto-off (seconds)", "talkLimit", initial.talkLimit, 5, 60, 5);
        audio.addView(text("Other apps may lower their audio while listening or talking. Kiosk Satellite's built-in music is unaffected.", 14));
        LinearLayout door = section(content, "Door lock (optional)", !initial.lockEntity.isEmpty());
        door.addView(text("Green: locked. Red: unlocked. Tap toggles lock/unlock without confirmation. Unknown/unavailable is hidden. Uses this kiosk's HA connection.", 14));
        Spinner lockPicker = new Spinner(context); door.addView(lockPicker); selectors.add(lockPicker);
        List<String> lockIds = new ArrayList<>(); lockIds.add(""); if (!initial.lockEntity.isEmpty()) lockIds.add(initial.lockEntity);
        List<String> lockNames = new ArrayList<>(); lockNames.add("None — hide lock button"); if (!initial.lockEntity.isEmpty()) lockNames.add(initial.lockEntity);
        lockPicker.setAdapter(adapter(lockNames)); lockPicker.setSelection(initial.lockEntity.isEmpty() ? 0 : 1); preferences.put("lockEntity", initial.lockEntity);
        lockPicker.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            public void onNothingSelected(android.widget.AdapterView<?> p) {}
            public void onItemSelected(android.widget.AdapterView<?> p, View v, int pos, long id) { if (pos >= 0 && pos < lockIds.size()) preferences.put("lockEntity", lockIds.get(pos)); }
        });
        TextView lockStatus = text("", 14); door.addView(lockStatus);
        Button loadLocks = button("Choose from Home Assistant locks"); door.addView(loadLocks);
        loadLocks.setOnClickListener(v -> {
            lockStatus.setText("Loading locks…");
            locks.openPicker((found, error) -> post(() -> {
                if (disposed) return;
                if (!error.isEmpty()) { lockStatus.setText(error); return; }
                String selected = Objects.toString(preferences.get("lockEntity"), "");
                lockIds.clear(); lockNames.clear(); lockIds.add(""); lockNames.add("None — hide lock button");
                for (HaLocks.Choice choice : found) { lockIds.add(choice.id); lockNames.add(choice.toString()); }
                if (!selected.isEmpty() && !lockIds.contains(selected)) { lockIds.add(selected); lockNames.add(selected + " (saved)"); }
                lockPicker.setAdapter(adapter(lockNames)); lockPicker.setSelection(Math.max(0, lockIds.indexOf(selected)));
                lockStatus.setText(found.isEmpty() ? "No locks returned by Home Assistant" : "Choose a lock, or None to hide the button");
            }));
        });
        save = button("Save and open doorbell"); save.setEnabled(false); content.addView(save);
        controller = new SetupController(values, apply, next -> post(() -> { if (!disposed && controllerStateIs(next)) render(next); }));
        TextWatcher edits = new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int st, int count, int after) {}
            public void onTextChanged(CharSequence s, int st, int before, int count) { controller.invalidate(); }
            public void afterTextChanged(Editable s) {}
        };
        console.addTextChangedListener(edits); key.addTextChangedListener(edits);
        validate.setOnClickListener(v -> controller.validate(console.getText().toString(), key.getText().toString()));
        accept.setOnClickListener(v -> {
            CertificateProbe.Offer offer = controller.state().certificate;
            if (offer != null) controller.acceptCertificate(offer.fingerprint);
        });
        cameras.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            public void onNothingSelected(android.widget.AdapterView<?> parent) {}
            public void onItemSelected(android.widget.AdapterView<?> parent, View v, int position, long id) {
                if (position < 0 || position >= choices.size()) return;
                DiscoveredCamera selected = choices.get(position);
                controller.chooseCamera(selected.id);
            }
        });
        streams.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            public void onNothingSelected(android.widget.AdapterView<?> parent) {}
            public void onItemSelected(android.widget.AdapterView<?> parent, View v, int position, long id) {
                if (updatingStreams || position < 0 || position > qualities.size()) return;
                String quality = position == 0 ? "" : qualities.get(position - 1);
                if (!quality.equals(controller.state().quality)) controller.chooseQuality(quality);
            }
        });
        save.setOnClickListener(v -> {
            try { controller.preferences(preferences); } catch (Exception e) { status.setText(Failures.describe(e, "Invalid settings")); return; }
            int at = cameras.getSelectedItemPosition();
            if (controller.canSavePreferences() ? controller.savePreferences() : at >= 0 && at < choices.size() && controller.select(choices.get(at).id, Objects.toString(controller.values().get("cameraAddress"), ""))) {
                dispose(); savedAction.run();
            }
        });
        Context app = context.getApplicationContext(); application = app instanceof Application ? (Application) app : null;
        if (application != null) application.registerActivityLifecycleCallbacks(this);
        render(controller.state());
        if (!initial.cameraId.isEmpty()) controller.validate(console.getText().toString(), key.getText().toString());
    }
    private boolean controllerStateIs(SetupController.State state) { return controller.state() == state; }
    private void render(SetupController.State state) {
        boolean ready = controller.validated();
        if (!ready || !gatingShown) for (View item : gated) item.setVisibility(ready ? VISIBLE : GONE);
        gatingShown = ready;
        status.setText(state.message); status.setTextColor(theme.color(state.error ? "error" : "onSurface"));
        validate.setEnabled(!state.busy); validate.setText(state.busy ? "Validating…" : "Validate connection");
        boolean offer = state.certificate != null;
        certificate.setVisibility(offer ? VISIBLE : GONE); accept.setVisibility(offer ? VISIBLE : GONE);
        certificate.setText(offer ? state.certificate.details : "");
        if (choices != state.cameras) {
            choices = state.cameras;
            List<String> names = new ArrayList<>(); int selected = 0;
            String saved = Objects.toString(controller.values().get("cameraId"), "");
            for (int i = 0; i < choices.size(); i++) {
                DiscoveredCamera c = choices.get(i); names.add(c.name);
                if (c.id.equals(saved)) selected = i;
            }
            ArrayAdapter<String> adapter = adapter(names);
            cameras.setAdapter(adapter); if (!names.isEmpty()) cameras.setSelection(selected);
        }
        if (qualities != state.streams) {
            qualities = state.streams; List<String> names = new ArrayList<>(); names.add(qualities.isEmpty() ? "No enabled streams" : "Select an enabled stream"); names.addAll(qualities);
            updatingStreams = true; streams.setAdapter(adapter(names));
            streams.setSelection(state.quality.isEmpty() ? 0 : qualities.indexOf(state.quality) + 1); updatingStreams = false;
        }
        save.setEnabled(controller.canSavePreferences() || !state.busy && !state.error && !offer && !choices.isEmpty() && state.streams.contains(state.quality));
        save.setAlpha(save.isEnabled() ? 1f : .5f);
        cameras.setEnabled(!offer && !choices.isEmpty()); streams.setEnabled(!state.busy && !state.error && !qualities.isEmpty());
    }
    private LinearLayout section(LinearLayout parent, String title, boolean expanded) {
        if (!"Connection / Doorbell".equals(title)) {
            LinearLayout wrapper = new LinearLayout(getContext()); wrapper.setOrientation(LinearLayout.VERTICAL);
            parent.addView(wrapper); gated.add(wrapper); parent = wrapper;
        }
        Button toggle = button(title + " ▾"); parent.addView(toggle);
        LinearLayout body = new LinearLayout(getContext()); body.setOrientation(LinearLayout.VERTICAL); body.setPadding(dp(12), dp(4), dp(12), dp(12));
        parent.addView(body); body.setVisibility(expanded ? VISIBLE : GONE);
        toggle.setOnClickListener(v -> body.setVisibility(body.getVisibility() == VISIBLE ? GONE : VISIBLE)); return body;
    }
    private void preference(LinearLayout parent, String title, String key, String value, String... options) {
        parent.addView(text(title, 16)); Spinner spinner = new Spinner(getContext()); selectors.add(spinner);
        spinner.setAdapter(adapter(Arrays.asList(options))); spinner.setSelection(Arrays.asList(options).indexOf(value));
        preferences.put(key, value); parent.addView(spinner);
        spinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            public void onNothingSelected(android.widget.AdapterView<?> p) {}
            public void onItemSelected(android.widget.AdapterView<?> p, View v, int at, long id) { if (at >= 0 && at < options.length) { preferences.put(key, options[at]); if ("sizing".equals(key)) updateCrop(); } }
        });
    }
    private void updateCrop() { if (crop != null) crop.setVisibility("Fill".equals(preferences.get("sizing")) ? VISIBLE : GONE); }
    private final List<SeekBar> sliders = new ArrayList<>();
    private void slider(LinearLayout parent, String title, String key, int value, int min, int max, int step) {
        TextView label = text(title + ": " + value, 16); parent.addView(label);
        SeekBar slider = new SeekBar(getContext()); sliders.add(slider); slider.setMax((max - min) / step); slider.setProgress((value - min) / step); theme.styleSlider(slider);
        preferences.put(key, value); parent.addView(slider);
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onStartTrackingTouch(SeekBar s) {} public void onStopTrackingTouch(SeekBar s) {}
            public void onProgressChanged(SeekBar s, int p, boolean user) { if (user) { int n = min + p * step; preferences.put(key, n); label.setText(title + ": " + n); } }
        });
    }
    private void flag(LinearLayout parent, String label, String key, boolean value) {
        CheckBox check = new CheckBox(getContext()); checks.add(check); check.setText(label); theme.styleText(check, 16, 400, "onSurface");
        check.setButtonTintList(android.content.res.ColorStateList.valueOf(theme.color("primary"))); check.setChecked(value); parent.addView(check); preferences.put(key, value);
        check.setOnCheckedChangeListener((b, selected) -> preferences.put(key, selected));
    }
    private ArrayAdapter<String> adapter(List<String> names) {
            ArrayAdapter<String> adapter = new ArrayAdapter<String>(getContext(), android.R.layout.simple_spinner_item, names) {
                public View getView(int position, View convertView, ViewGroup parent) {
                    return styleChoice(super.getView(position, convertView, parent));
                }
                public View getDropDownView(int position, View convertView, ViewGroup parent) {
                    return styleChoice(super.getDropDownView(position, convertView, parent));
                }
            };
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            return adapter;
    }
    private EditText field(LinearLayout content, String label, boolean secret) {
        content.addView(text(label, 16)); EditText input = new EditText(getContext()); inputs.add(input); styleInput(input);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | (secret ? android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD : android.text.InputType.TYPE_TEXT_VARIATION_URI));
        input.setSingleLine(true);
        // setSingleLine can replace the transformation method. Apply password
        // masking last, and never put the secret into saved view/autofill state.
        if (secret) {
            input.setTransformationMethod(android.text.method.PasswordTransformationMethod.getInstance());
            input.setSaveEnabled(false);
            if (android.os.Build.VERSION.SDK_INT >= 26) input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        }
        content.addView(input); return input;
    }
    private View styleChoice(View view) {
        if (view instanceof TextView) {
            ThemeUi.choice((TextView) view, theme);
        }
        return view;
    }
    private TextView text(String value, int size) { TextView t = new TextView(getContext()); t.setText(value); theme.styleText(t, size, size >= 20 ? 600 : 400, "onSurface"); t.setPadding(0, dp(6), 0, dp(6)); labels.add(t); return t; }
    private Button button(String value) { Button b = new Button(getContext()); b.setText(value); theme.stylePill(b, true); b.setMinHeight(dp(48)); buttons.add(b); return b; }
    private void styleInput(EditText input) {
        theme.styleText(input, 16, 400, "onSurface"); input.setHintTextColor(theme.color("onSurfaceVariant"));
        input.setBackground(ThemeUi.surface(theme, "surfaceContainerHigh", theme.radiusControl())); input.setPadding(dp(12), dp(8), dp(12), dp(8));
    }
    void applyTheme(KsTheme next) {
        theme = next; setBackgroundColor(theme.color("surface"));
        for (TextView label : labels) theme.styleText(label, label.getTextSize() / getResources().getDisplayMetrics().scaledDensity, label.getTextSize() / getResources().getDisplayMetrics().scaledDensity >= 20 ? 600 : 400, "onSurface");
        for (EditText input : inputs) styleInput(input);
        for (Button button : buttons) theme.stylePill(button, true);
        for (CheckBox check : checks) { theme.styleText(check, 16, 400, "onSurface"); check.setButtonTintList(android.content.res.ColorStateList.valueOf(theme.color("primary"))); }
        for (SeekBar slider : sliders) theme.styleSlider(slider);
        for (Spinner spinner : selectors) if (spinner.getAdapter() instanceof ArrayAdapter) ((ArrayAdapter<?>) spinner.getAdapter()).notifyDataSetChanged();
        if (initialProblem != null) initialProblem.setTextColor(theme.color("error"));
        if (cameras.getAdapter() instanceof ArrayAdapter) ((ArrayAdapter<?>) cameras.getAdapter()).notifyDataSetChanged();
        if (streams.getAdapter() instanceof ArrayAdapter) ((ArrayAdapter<?>) streams.getAdapter()).notifyDataSetChanged();
        render(controller.state());
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    void cancelWork() { controller.close(); }
    void dispose() { if (disposed) return; disposed = true; controller.close(); locks.closePicker(); key.setText(""); if (application != null) application.unregisterActivityLifecycleCallbacks(this); }
    @Override protected void onDetachedFromWindow() { dispose(); super.onDetachedFromWindow(); }
    public void onActivityPaused(Activity a) { controller.close(); post(() -> { dispose(); backgroundAction.run(); }); }
    public void onActivityStopped(Activity a) { onActivityPaused(a); }
    public void onActivityDestroyed(Activity a) { onActivityPaused(a); }
    public void onActivityCreated(Activity a, Bundle state) {}
    public void onActivityStarted(Activity a) {}
    public void onActivityResumed(Activity a) {}
    public void onActivitySaveInstanceState(Activity a, Bundle state) {}
}
