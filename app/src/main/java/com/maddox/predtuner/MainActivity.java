package com.maddox.predtuner;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {

    private static final String HOST = "127.0.0.1";
    private static final Pattern PROP_NAME = Pattern.compile("[A-Za-z0-9._\\-]{1,92}");
    private static final Pattern NUMBER = Pattern.compile("-?[0-9]+(\\.[0-9]+)?");
    private static final Pattern GETPROP_LINE = Pattern.compile("^\\[([^\\]]+)\\]: \\[(.*)\\]$");

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final List<Control> controls = new ArrayList<>();
    private final List<String> logLines = new ArrayList<>();

    private SharedPreferences prefs;
    private TextView statusText;
    private TextView logText;
    private EditText pairPortInput;
    private EditText pairCodeInput;
    private EditText connectPortInput;
    private CheckBox autoApplyBox;
    private LinearLayout scanList;

    private volatile boolean connected = false;
    private boolean triedAutoConnect = false;

    /** One setting with − / + buttons. */
    private final class Control {
        final String id;
        final String label;
        final String defaultProp;
        final double[] choices;     // null = any number, moved by the step box
        final double defaultStart;  // value to start from if the setting isn't set yet
        final double defaultStep;
        EditText propInput;
        EditText stepInput;
        TextView valueText;

        Control(String id, String label, String defaultProp, double[] choices,
                double defaultStart, double defaultStep) {
            this.id = id;
            this.label = label;
            this.defaultProp = defaultProp;
            this.choices = choices;
            this.defaultStart = defaultStart;
            this.defaultStep = defaultStep;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("tuner", MODE_PRIVATE);

        controls.add(new Control("phase", "Phase Sync  (1 = less prediction lag)",
                "debug.oculus.phaseSync", new double[]{0, 1}, 0, 1));
        controls.add(new Control("refresh", "Refresh rate (Hz)  (higher = shorter prediction)",
                "debug.oculus.refreshRate", new double[]{60, 72, 80, 90, 120}, 72, 1));
        controls.add(new Control("custom1", "Custom 1", "", null, 0, 1));
        controls.add(new Control("custom2", "Custom 2", "", null, 0, 1));

        setContentView(buildUi());
        setStatus("Not connected", false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!connected && !triedAutoConnect) {
            triedAutoConnect = true;
            connect(null, true);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdown();
    }

    // ------------------------------------------------------------------ UI

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(20), dp(16), dp(20), dp(24));
        scroll.addView(col);

        col.addView(text("Quest Pred Tuner", 26, true));
        statusText = text("", 16, true);
        col.addView(statusText);

        // --- Connect
        col.addView(header("1. Connect"));
        col.addView(text(
                "First time only: Settings → System → Developer → turn on Wireless debugging "
                        + "(tap Always allow). Tap the words \"Wireless debugging\", then \"Pair device "
                        + "with pairing code\". Keep that window open next to this app, type the port "
                        + "(the numbers after the colon) and the 6-digit code below, then tap Pair.\n\n"
                        + "After every headset restart: turn Wireless debugging back on, then tap Connect. "
                        + "If Connect can't find it, type the port shown under \"IP address & Port\" "
                        + "on the Wireless debugging screen.", 14, false));

        LinearLayout pairRow = row();
        pairPortInput = input("Pairing port", InputType.TYPE_CLASS_NUMBER);
        pairCodeInput = input("6-digit code", InputType.TYPE_CLASS_NUMBER);
        pairRow.addView(pairPortInput, weight(1));
        pairRow.addView(pairCodeInput, weight(1));
        pairRow.addView(button("Pair", v -> pair()));
        col.addView(pairRow);

        LinearLayout connectRow = row();
        connectPortInput = input("Connect port (optional)", InputType.TYPE_CLASS_NUMBER);
        connectRow.addView(connectPortInput, weight(2));
        connectRow.addView(button("Connect", v -> connectFromInput()));
        col.addView(connectRow);

        // --- Controls
        col.addView(header("2. Tune"));
        col.addView(text("Settings apply to games you open after changing them. "
                + "Everything resets when the headset restarts.", 14, false));
        for (Control c : controls) {
            col.addView(buildControl(c));
        }

        autoApplyBox = new CheckBox(this);
        autoApplyBox.setText("Re-apply my saved values every time I connect");
        autoApplyBox.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        autoApplyBox.setChecked(prefs.getBoolean("autoApply", true));
        autoApplyBox.setOnCheckedChangeListener((b, checked) ->
                prefs.edit().putBoolean("autoApply", checked).apply());
        col.addView(autoApplyBox);

        LinearLayout actions = row();
        actions.addView(button("Re-apply all now", v -> applyAllSaved()), weight(1));
        actions.addView(button("Scan Quest settings", v -> scan()), weight(1));
        col.addView(actions);
        col.addView(button("Find hidden prediction settings", v -> findHidden()));

        // --- Scan results
        scanList = new LinearLayout(this);
        scanList.setOrientation(LinearLayout.VERTICAL);
        col.addView(scanList);

        // --- Log
        col.addView(header("Log"));
        logText = text("", 13, false);
        logText.setTypeface(Typeface.MONOSPACE);
        col.addView(logText);

        return scroll;
    }

    private View buildControl(Control c) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(10), dp(12), dp(10));
        box.setBackgroundColor(Color.argb(40, 255, 255, 255));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        box.setLayoutParams(lp);

        box.addView(text(c.label, 17, true));

        c.propInput = input("setting name, e.g. debug.oculus.xxx", InputType.TYPE_CLASS_TEXT);
        c.propInput.setText(prefs.getString("prop_" + c.id, c.defaultProp));
        box.addView(c.propInput);

        LinearLayout r = row();
        r.addView(button("−", v -> nudge(c, -1)));
        c.valueText = text(savedValue(c).isEmpty() ? "—" : savedValue(c), 24, true);
        c.valueText.setGravity(Gravity.CENTER);
        r.addView(c.valueText, weight(1));
        r.addView(button("+", v -> nudge(c, +1)));
        if (c.choices == null) {
            c.stepInput = input("step", InputType.TYPE_CLASS_NUMBER
                    | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
            c.stepInput.setText(prefs.getString("step_" + c.id, fmt(c.defaultStep)));
            r.addView(c.stepInput, new LinearLayout.LayoutParams(dp(90),
                    LinearLayout.LayoutParams.WRAP_CONTENT));
        }
        r.addView(button("Reset", v -> reset(c)));
        box.addView(r);
        return box;
    }

    // ------------------------------------------------------------ actions

    private void pair() {
        String port = pairPortInput.getText().toString().trim();
        String code = pairCodeInput.getText().toString().trim();
        if (!port.matches("[0-9]{2,5}") || !code.matches("[0-9]{6}")) {
            log("Type the pairing port and the 6-digit code first.");
            return;
        }
        log("Pairing on port " + port + "…");
        io.execute(() -> {
            try {
                AdbManager.get(this).pair(HOST, Integer.parseInt(port), code);
                log("Paired! Connecting…");
                doConnect(null, false);
            } catch (Throwable t) {
                log("Pairing failed: " + describe(t)
                        + "\nTip: make a new code, keep both windows open, or restart the headset and try again.");
            }
        });
    }

    private void connectFromInput() {
        String port = connectPortInput.getText().toString().trim();
        if (!port.isEmpty() && !port.matches("[0-9]{2,5}")) {
            log("Connect port must be a number.");
            return;
        }
        connect(port.isEmpty() ? null : Integer.parseInt(port), false);
    }

    private void connect(Integer port, boolean quiet) {
        io.execute(() -> doConnect(port, quiet));
    }

    /** Runs on the io thread. */
    private void doConnect(Integer port, boolean quiet) {
        try {
            AdbManager mgr = AdbManager.get(this);
            if (!mgr.isConnected()) {
                if (!quiet) log(port == null ? "Looking for wireless debugging…" : "Connecting to port " + port + "…");
                if (port != null) {
                    mgr.connect(HOST, port);
                } else {
                    mgr.autoConnect(getApplicationContext(), 6000);
                }
            }
            connected = mgr.isConnected();
        } catch (Throwable t) {
            connected = false;
            if (!quiet) log("Connect failed: " + describe(t));
        }

        if (connected) {
            setStatus("Connected ✔", true);
            log("Connected to the headset.");
            if (prefs.getBoolean("autoApply", true)) {
                applyAllSavedNow();
            }
            refreshAllNow();
        } else {
            setStatus("Not connected", false);
            if (!quiet) log("Not connected. Is Wireless debugging on? Have you paired yet?");
        }
    }

    private void nudge(Control c, int dir) {
        String prop = c.propInput.getText().toString().trim();
        if (!PROP_NAME.matcher(prop).matches()) {
            log(c.label + ": type a valid setting name first.");
            return;
        }
        double step = c.defaultStep;
        if (c.stepInput != null) {
            String s = c.stepInput.getText().toString().trim();
            if (!NUMBER.matcher(s).matches() || Double.parseDouble(s) == 0) {
                log(c.label + ": step must be a number that isn't 0.");
                return;
            }
            step = Math.abs(Double.parseDouble(s));
            prefs.edit().putString("step_" + c.id, s).apply();
        }
        prefs.edit().putString("prop_" + c.id, prop).apply();
        if (!requireConnected()) return;

        final double stepFinal = step;
        io.execute(() -> {
            try {
                AdbManager mgr = AdbManager.get(this);
                String cur = mgr.shell("getprop " + prop);
                double current = NUMBER.matcher(cur).matches() ? Double.parseDouble(cur) : c.defaultStart;
                double next;
                if (c.choices != null) {
                    int idx = nearestIndex(c.choices, current);
                    if (!NUMBER.matcher(cur).matches() && dir < 0) idx++; // unset: step down from the default
                    idx = Math.max(0, Math.min(c.choices.length - 1, idx + dir));
                    next = c.choices[idx];
                } else {
                    next = current + dir * stepFinal;
                }
                setAndShow(mgr, c, prop, fmt(next));
            } catch (Throwable t) {
                log(c.label + " failed: " + describe(t));
                markDisconnectedIfNeeded();
            }
        });
    }

    private void reset(Control c) {
        String prop = c.propInput.getText().toString().trim();
        if (!PROP_NAME.matcher(prop).matches()) {
            log(c.label + ": type a valid setting name first.");
            return;
        }
        if (!requireConnected()) return;
        io.execute(() -> {
            try {
                AdbManager mgr = AdbManager.get(this);
                mgr.shell("setprop " + prop + " ''");
                prefs.edit().remove("val_" + c.id).apply();
                runOnUiThread(() -> c.valueText.setText("—"));
                log("Reset " + prop + " (back to the game's own value).");
            } catch (Throwable t) {
                log("Reset failed: " + describe(t));
                markDisconnectedIfNeeded();
            }
        });
    }

    private void applyAllSaved() {
        if (!requireConnected()) return;
        io.execute(this::applyAllSavedNow);
    }

    /** Runs on the io thread. */
    private void applyAllSavedNow() {
        int count = 0;
        for (Control c : controls) {
            String prop = prefs.getString("prop_" + c.id, c.defaultProp);
            String val = savedValue(c);
            if (prop == null || !PROP_NAME.matcher(prop).matches() || !NUMBER.matcher(val).matches()) {
                continue;
            }
            try {
                setAndShow(AdbManager.get(this), c, prop, val);
                count++;
            } catch (Throwable t) {
                log("Couldn't re-apply " + prop + ": " + describe(t));
            }
        }
        log(count == 0 ? "Nothing saved to re-apply yet." : "Re-applied " + count + " saved value(s).");
    }

    /** Runs on the io thread. Shows what each setting is right now. */
    private void refreshAllNow() {
        for (Control c : controls) {
            String prop = prefs.getString("prop_" + c.id, c.defaultProp);
            if (prop == null || !PROP_NAME.matcher(prop).matches()) continue;
            try {
                String cur = AdbManager.get(this).shell("getprop " + prop);
                runOnUiThread(() -> c.valueText.setText(cur.isEmpty() ? "—" : cur));
            } catch (Throwable ignored) {
            }
        }
    }

    private void scan() {
        if (!requireConnected()) return;
        log("Scanning…");
        io.execute(() -> {
            try {
                String out = AdbManager.get(this).shell("getprop");
                List<String[]> found = new ArrayList<>();
                for (String line : out.split("\n")) {
                    Matcher m = GETPROP_LINE.matcher(line.trim());
                    if (!m.matches()) continue;
                    String name = m.group(1);
                    String value = m.group(2);
                    boolean interesting = name.startsWith("debug.")
                            || name.toLowerCase(Locale.US).contains("predict");
                    if (interesting && !value.isEmpty()) {
                        found.add(new String[]{name, value});
                    }
                }
                Collections.sort(found, (a, b) -> a[0].compareTo(b[0]));
                runOnUiThread(() -> showScan("Currently set on your Quest", found));
                log("Found " + found.size() + " debug setting(s). Tap one to load it into a Custom row.");
            } catch (Throwable t) {
                log("Scan failed: " + describe(t));
                markDisconnectedIfNeeded();
            }
        });
    }

    /**
     * Searches the Quest's system libraries and programs for setting names that mention
     * prediction / extrapolation / latency. These can exist even when they're not set,
     * so the normal Scan can't see them.
     */
    private void findHidden() {
        if (!requireConnected()) return;
        log("Searching system files for hidden settings… (can take up to a minute)");
        String cmd = "{ find /system/lib64 /system/bin /vendor/lib64 /vendor/bin"
                + " /system_ext/lib64 /system_ext/bin /product/lib64 -type f;"
                + " find /system/priv-app /system_ext/priv-app /product/priv-app /system/app -name '*.so'; }"
                + " 2>/dev/null | xargs grep -a -o -h -E"
                + " '[a-z]+\\.[a-z0-9_]+\\.[A-Za-z0-9_.]*([Pp]redict|PREDICT|[Ee]xtrapolat|[Ll]atency)[A-Za-z0-9_.]*'"
                + " 2>/dev/null | sort -u | head -300";
        io.execute(() -> {
            try {
                AdbManager mgr = AdbManager.get(this);
                String out = mgr.shell(cmd);
                List<String[]> found = new ArrayList<>();
                for (String line : out.split("\n")) {
                    String name = line.trim();
                    if (name.endsWith(".")) name = name.substring(0, name.length() - 1);
                    if (!PROP_NAME.matcher(name).matches()) continue;
                    // Only names the shell is normally allowed to change
                    if (!name.startsWith("debug.") && !name.startsWith("vendor.debug.")) continue;
                    String cur = mgr.shell("getprop " + name);
                    found.add(new String[]{name, cur.isEmpty() ? "(not set)" : cur});
                }
                runOnUiThread(() -> showScan("Hidden prediction-related settings", found));
                log(found.isEmpty()
                        ? "No hidden prediction settings found in system files."
                        : "Found " + found.size() + " hidden setting name(s). Tap one to load it, then try +/−.");
            } catch (Throwable t) {
                log("Search failed: " + describe(t));
                markDisconnectedIfNeeded();
            }
        });
    }

    private void showScan(String title, List<String[]> found) {
        scanList.removeAllViews();
        if (found.isEmpty()) {
            scanList.addView(text("Nothing found.", 14, false));
            return;
        }
        scanList.addView(header(title));
        for (String[] p : found) {
            Button b = button(p[0] + " = " + p[1], v -> loadIntoCustom(p[0], p[1]));
            b.setAllCaps(false);
            b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            scanList.addView(b);
        }
    }

    private void loadIntoCustom(String prop, String value) {
        Control target = null;
        for (Control c : controls) {
            if (c.choices == null && c.propInput.getText().toString().trim().isEmpty()) {
                target = c;
                break;
            }
        }
        if (target == null) target = controls.get(controls.size() - 2); // Custom 1
        target.propInput.setText(prop);
        target.valueText.setText(NUMBER.matcher(value).matches() ? value : "—");
        prefs.edit().putString("prop_" + target.id, prop).apply();
        if (NUMBER.matcher(value).matches()) {
            prefs.edit().putString("val_" + target.id, value).apply();
        }
        log("Loaded " + prop + " into " + target.label + ".");
    }

    // ------------------------------------------------------------- helpers

    /** Runs on the io thread. */
    private void setAndShow(AdbManager mgr, Control c, String prop, String value) throws Exception {
        mgr.shell("setprop " + prop + " " + value);
        String now = mgr.shell("getprop " + prop);
        prefs.edit().putString("val_" + c.id, now).apply();
        runOnUiThread(() -> c.valueText.setText(now.isEmpty() ? "—" : now));
        if (now.equals(value)) {
            log(prop + " → " + now);
        } else {
            log(prop + " didn't change (Quest blocked it or it's read-only). Now: " + now);
        }
    }

    private boolean requireConnected() {
        if (!connected) {
            log("Not connected yet — do step 1 first.");
            return false;
        }
        return true;
    }

    private void markDisconnectedIfNeeded() {
        try {
            if (!AdbManager.get(this).isConnected()) {
                connected = false;
                setStatus("Not connected", false);
            }
        } catch (Throwable ignored) {
        }
    }

    private String savedValue(Control c) {
        String v = prefs.getString("val_" + c.id, "");
        return v == null ? "" : v;
    }

    private static int nearestIndex(double[] arr, double v) {
        int best = 0;
        for (int i = 1; i < arr.length; i++) {
            if (Math.abs(arr[i] - v) < Math.abs(arr[best] - v)) best = i;
        }
        return best;
    }

    private static String fmt(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e15) {
            return String.valueOf((long) v);
        }
        String s = String.format(Locale.US, "%.4f", v);
        s = s.replaceAll("0+$", "");
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }

    private static String describe(Throwable t) {
        String msg = t.getMessage();
        return t.getClass().getSimpleName() + (msg == null ? "" : ": " + msg);
    }

    private void setStatus(String s, boolean ok) {
        runOnUiThread(() -> {
            statusText.setText(s);
            statusText.setTextColor(ok ? Color.rgb(90, 220, 120) : Color.rgb(240, 110, 110));
        });
    }

    private void log(String line) {
        runOnUiThread(() -> {
            logLines.add(0, line);
            while (logLines.size() > 40) logLines.remove(logLines.size() - 1);
            logText.setText(String.join("\n", logLines));
        });
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private TextView text(String s, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(4), 0, dp(4));
        return t;
    }

    private TextView header(String s) {
        TextView t = text(s, 20, true);
        t.setPadding(0, dp(18), 0, dp(6));
        return t;
    }

    private EditText input(String hint, int type) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setInputType(type);
        e.setSingleLine(true);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        return e;
    }

    private Button button(String label, View.OnClickListener onClick) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        b.setMinimumWidth(dp(72));
        b.setMinimumHeight(dp(56));
        b.setOnClickListener(onClick);
        return b;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }

    private static LinearLayout.LayoutParams weight(float w) {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, w);
    }
}
