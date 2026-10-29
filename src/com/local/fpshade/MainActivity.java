package com.local.fpshade;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.hardware.fingerprint.FingerprintManager;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Switch;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Root-free fingerprint-to-action app.
 *
 * Single-shot by design: one arm = exactly one authentication session. On a
 * match it performs one accessibility global action and disarms. Nothing
 * re-arms automatically, so the sensor cannot loop.
 */
public class MainActivity extends Activity {

    private static final String TAG = "FPShade";

    static final String PREFS = "fpshade";
    static final String KEY_ACTION = "action_id";
    static final String KEY_QUICK_LEGACY = "action_quick";
    static final String KEY_ONBOARDED = "onboarded";
    static final String KEY_HEADLESS = "headless";
    static final String KEY_APP_COMPONENT = "app_component";
    static final String KEY_APP_LABEL = "app_label";
    static final String EXTRA_SETTINGS = "settings";

    private static final int ERR_HW_UNAVAILABLE = 1;
    private static final int ERR_UNABLE_TO_PROCESS = 2;
    private static final int ERR_TIMEOUT = 3;
    private static final int ERR_NO_SPACE = 4;
    private static final int ERR_CANCELED = 5;
    private static final int ERR_LOCKOUT = 7;
    private static final int ERR_VENDOR = 8;
    private static final int ERR_LOCKOUT_PERMANENT = 9;
    private static final int ERR_USER_CANCELED = 10;

    private static final int MAX_FAILS_BEFORE_STOP = 4;

    private TextView statusView;
    private TextView logView;
    private TextView setupView;
    private TextView actionDescView;
    private Button armButton;
    private Button chooseAppButton;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private boolean running;
    private boolean paused;
    private boolean testingHeadless;
    private boolean pickingApp;
    private int failStreak;

    private Actions.Action selectedAction;
    private Actions.Action lastNonAppAction;

    private FingerprintManager fingerprintManager;
    private CancellationSignal cancelSignal;

    private final Runnable armRunnable = new Runnable() {
        @Override
        public void run() {
            if (running && !paused) armSession();
        }
    };

    /** Keeps the accessibility dot current while the GUI is open. */
    private final Runnable statusRunnable = new Runnable() {
        @Override
        public void run() {
            refreshStatus();
            handler.postDelayed(this, 1500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusView = findViewById(R.id.status);
        logView = findViewById(R.id.log);
        setupView = findViewById(R.id.setup);
        actionDescView = findViewById(R.id.actionDesc);
        armButton = findViewById(R.id.arm);
        chooseAppButton = findViewById(R.id.chooseApp);

        buildActionList();
        buildHeadlessToggle();
        buildButtons();

        fingerprintManager =
                (FingerprintManager) getSystemService(Context.FINGERPRINT_SERVICE);

        log("Ready.");
        logHardwareInfo();
        if (!prefs().getBoolean(KEY_ONBOARDED, false)) {
            log("First run: enable accessibility, then bind FP Shade to the side key.");
            prefs().edit().putBoolean(KEY_ONBOARDED, true).apply();
        }
        refreshStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        paused = false;
        testingHeadless = false;
        refreshStatus();
        handler.removeCallbacks(statusRunnable);
        handler.postDelayed(statusRunnable, 700);
    }

    @Override
    protected void onPause() {
        paused = true;
        handler.removeCallbacks(statusRunnable);
        cancelCurrent();
        // In headless mode the GUI is only a settings screen. It must not stay
        // alive behind the transparent headless window (which would show it).
        // onPause fires even when the activity is only covered by a
        // translucent window, so the check belongs here, not in onStop.
        if (testingHeadless) {
            testingHeadless = false;
        } else if (!isChangingConfigurations()
                && !isFinishing()
                && prefs().getBoolean(KEY_HEADLESS, false)) {
            finish();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        running = false;
        cancelCurrent();
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    // ------------------------------------------------------------------- setup

    private void buildActionList() {
        RadioGroup group = findViewById(R.id.actions);
        List<Actions.Action> actions = Actions.available();

        Actions.Action restored = resolveStoredAction();
        int lastSection = -1;
        for (Actions.Action action : actions) {
            if (action.sectionRes != lastSection) {
                lastSection = action.sectionRes;
                group.addView(sectionHeader(getString(action.sectionRes)));
            }

            RadioButton option = new RadioButton(this);
            option.setId(View.generateViewId());
            option.setText(action.labelRes);
            option.setTag(action);
            option.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            option.setTextColor(getColor(R.color.text));
            option.setButtonTintList(getColorStateList(R.color.radio_tint));
            option.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            option.setPadding(0, dp(6), 0, dp(6));
            group.addView(option,
                    new RadioGroup.LayoutParams(
                            RadioGroup.LayoutParams.MATCH_PARENT,
                            RadioGroup.LayoutParams.WRAP_CONTENT));

            if (action.id.equals(restored.id)) option.setChecked(true);
        }

        selectedAction = restored;
        if (!restored.appLaunch) {
            lastNonAppAction = restored;
        } else if (lastNonAppAction == null) {
            lastNonAppAction = Actions.defaultAction();
        }
        updateActionUi();

        group.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup g, int checkedId) {
                View checked = g.findViewById(checkedId);
                if (checked == null || !(checked.getTag() instanceof Actions.Action)) return;
                selectedAction = (Actions.Action) checked.getTag();
                prefs().edit().putString(KEY_ACTION, selectedAction.id).apply();
                if (!selectedAction.appLaunch) lastNonAppAction = selectedAction;
                updateActionUi();
                if (selectedAction.appLaunch && getAppComponent() == null && !pickingApp) {
                    // First time this action is picked: ask which app, and go
                    // back to the previous action if the picker is cancelled.
                    pickApp(true);
                }
            }
        });
    }

    /** Updates the description line and the "choose app" button. */
    private void updateActionUi() {
        if (selectedAction == null) return;
        if (!selectedAction.appLaunch) {
            actionDescView.setText(selectedAction.descRes);
            chooseAppButton.setVisibility(View.GONE);
            refreshStatus();
            return;
        }
        String label = chosenAppLabel();
        chooseAppButton.setVisibility(View.VISIBLE);
        if (label == null) {
            chooseAppButton.setText(R.string.choose_app);
            actionDescView.setText(R.string.action_launch_app_desc);
        } else {
            chooseAppButton.setText(getString(R.string.change_app, label));
            actionDescView.setText(getString(R.string.action_launch_app_desc_set, label));
        }
        refreshStatus();
    }

    private String getAppComponent() {
        return prefs().getString(KEY_APP_COMPONENT, null);
    }

    private String chosenAppLabel() {
        String flat = getAppComponent();
        if (flat == null) return null;
        String label = prefs().getString(KEY_APP_LABEL, null);
        if (label == null) label = AppLauncher.label(this, flat);
        return label;
    }

    /**
     * Shows the app picker. When {@code revertOnCancel} is true and no app ends
     * up chosen, the action list returns to the previously selected action so
     * the sensor is never left pointing at nothing.
     */
    private void pickApp(final boolean revertOnCancel) {
        final List<AppLauncher.Entry> apps = AppLauncher.launchable(this);
        if (apps.isEmpty()) {
            log("no launchable apps found");
            if (revertOnCancel) revertToLastAction();
            return;
        }
        pickingApp = true;
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.pick_app_title)
                .setAdapter(new AppAdapter(apps), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        AppLauncher.Entry entry = apps.get(which);
                        prefs().edit()
                                .putString(KEY_APP_COMPONENT, entry.flat)
                                .putString(KEY_APP_LABEL, entry.label)
                                .apply();
                        log("app selected: " + entry.label);
                        updateActionUi();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface d) {
                pickingApp = false;
                updateActionUi();
                if (revertOnCancel && getAppComponent() == null) {
                    revertToLastAction();
                }
            }
        });
        dialog.show();
    }

    private void revertToLastAction() {
        Actions.Action target =
                lastNonAppAction != null ? lastNonAppAction : Actions.defaultAction();
        RadioGroup group = findViewById(R.id.actions);
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (!(child instanceof RadioButton)
                    || !(child.getTag() instanceof Actions.Action)) continue;
            Actions.Action action = (Actions.Action) child.getTag();
            if (action.id.equals(target.id)) {
                ((RadioButton) child).setChecked(true);
                return;
            }
        }
    }

    /** Icon + name + package for the app picker. */
    private final class AppAdapter extends BaseAdapter {

        private final List<AppLauncher.Entry> items;

        AppAdapter(List<AppLauncher.Entry> items) {
            this.items = items;
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view = convertView;
            if (view == null) {
                view = getLayoutInflater().inflate(R.layout.item_app, parent, false);
            }
            AppLauncher.Entry entry = items.get(position);
            ImageView icon = view.findViewById(R.id.appIcon);
            TextView name = view.findViewById(R.id.appName);
            TextView pkg = view.findViewById(R.id.appPkg);
            name.setText(entry.label);
            pkg.setText(entry.pkg);
            try {
                icon.setImageDrawable(entry.info.loadIcon(getPackageManager()));
            } catch (Throwable t) {
                icon.setImageDrawable(null);
            }
            return view;
        }
    }

    private Actions.Action resolveStoredAction() {
        Actions.Action stored = Actions.byId(prefs().getString(KEY_ACTION, null));
        if (stored == null) {
            // Migrate the old two-option setting.
            boolean quick = prefs().getBoolean(KEY_QUICK_LEGACY, true);
            stored = Actions.byId(quick ? "quick_settings" : "notifications");
        }
        if (stored == null || !isAvailable(stored)) stored = Actions.defaultAction();
        prefs().edit().putString(KEY_ACTION, stored.id).apply();
        return stored;
    }

    private boolean isAvailable(Actions.Action action) {
        for (Actions.Action a : Actions.available()) {
            if (a.id.equals(action.id)) return true;
        }
        return false;
    }

    private TextView sectionHeader(String title) {
        TextView header = new TextView(this);
        header.setText(title);
        header.setAllCaps(true);
        header.setLetterSpacing(0.08f);
        header.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        header.setTextColor(getColor(R.color.text_dim));
        header.setPadding(dp(4), dp(12), 0, dp(2));
        return header;
    }

    private void buildHeadlessToggle() {
        Switch headless = findViewById(R.id.headlessToggle);
        headless.setChecked(prefs().getBoolean(KEY_HEADLESS, false));
        headless.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                prefs().edit().putBoolean(KEY_HEADLESS, isChecked).apply();
                log(getString(isChecked ? R.string.headless_on : R.string.headless_off));
                refreshStatus();
            }
        });
    }

    private void buildButtons() {
        armButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (running) stop("stopped by user");
                else start();
            }
        });

        findViewById(R.id.accbtn).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                } catch (Throwable t) {
                    log("cannot open accessibility settings: " + t);
                }
            }
        });

        findViewById(R.id.headlessbtn).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    log("launching headless (transparent)");
                    testingHeadless = true;
                    startActivity(new Intent(MainActivity.this, HeadlessActivity.class));
                } catch (Throwable t) {
                    testingHeadless = false;
                    log("cannot launch headless: " + t);
                }
            }
        });

        chooseAppButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickApp(false);
            }
        });
    }

    // ---------------------------------------------------------------- control

    private void start() {
        if (!hasFingerprintHardware()) {
            log("Fingerprint hardware/enrollment not reported. Add a fingerprint first.");
            return;
        }
        if (actionNeedsAccessibility() && !FpAccessibilityService.isConnected()) {
            log("Note: accessibility service is off, so the action will not fire.");
            log("Enable FP Shade in Settings > Accessibility to test the full path.");
        }
        failStreak = 0;
        running = true;
        armButton.setText(R.string.stop);
        armButton.setBackgroundResource(R.drawable.btn_danger);
        refreshStatus();
        log("Armed → " + (selectedAction != null
                ? getString(selectedAction.labelRes) : "default action"));
        handler.removeCallbacks(armRunnable);
        handler.postDelayed(armRunnable, 250);
    }

    private boolean actionNeedsAccessibility() {
        return selectedAction == null || !selectedAction.appLaunch;
    }

    private void stop(String reason) {
        running = false;
        handler.removeCallbacks(armRunnable);
        cancelCurrent();
        armButton.setText(R.string.arm_now);
        armButton.setBackgroundResource(R.drawable.btn_primary);
        refreshStatus();
        log("Stopped (" + reason + ")");
    }

    private void cancelCurrent() {
        try {
            if (cancelSignal != null) cancelSignal.cancel();
        } catch (Throwable ignored) {
        }
        cancelSignal = null;
    }

    // ----------------------------------------------------------------- session

    @SuppressWarnings("deprecation")
    private void armSession() {
        cancelCurrent();
        cancelSignal = new CancellationSignal();

        try {
            fingerprintManager.authenticate(null, cancelSignal, 0,
                    new FingerprintManager.AuthenticationCallback() {
                        @Override
                        public void onAuthenticationSucceeded(FingerprintManager.AuthenticationResult result) {
                            MainActivity.this.onMatch();
                        }

                        @Override
                        public void onAuthenticationFailed() {
                            MainActivity.this.onNoMatch();
                        }

                        @Override
                        public void onAuthenticationError(int code, CharSequence errString) {
                            MainActivity.this.onAuthError(code, String.valueOf(errString));
                        }
                    }, handler);
            log("Touch the sensor once.");
        } catch (Throwable t) {
            log("session failed to start: " + t);
            stop("session error");
        }
    }

    // --------------------------------------------------------------- callbacks

    private void onMatch() {
        failStreak = 0;
        Actions.Action action = selectedAction != null
                ? selectedAction : Actions.defaultAction();
        boolean ok;
        if (action.appLaunch) {
            String flat = getAppComponent();
            ok = AppLauncher.launch(this, flat);
            String label = chosenAppLabel();
            if (ok) {
                log("MATCH → " + (label != null ? label : "app")
                        + " (" + getString(action.labelRes) + ")");
            } else {
                log("MATCH → no app chosen, or it is no longer installed");
            }
        } else {
            ok = FpAccessibilityService.fire(action.globalAction);
            log(ok
                    ? "MATCH → " + getString(action.labelRes)
                    : "MATCH → " + getString(action.labelRes) + " (action was rejected)");
        }
        stop(ok ? "done, one action fired" : "done, action rejected");
    }

    private void onNoMatch() {
        failStreak++;
        log("no match (attempt #" + failStreak + ")");
        if (failStreak >= MAX_FAILS_BEFORE_STOP) {
            stop("too many failed touches, stopping before the system lockout");
        }
    }

    private void onAuthError(int code, String msg) {
        if (!running || paused) return;

        log("sensor error " + code + ": " + msg);

        if (code == ERR_LOCKOUT) {
            stop("sensor locked out, wait ~30s");
            return;
        }
        if (code == ERR_LOCKOUT_PERMANENT) {
            stop("permanent lockout, unlock with PIN first");
            return;
        }
        if (code == ERR_CANCELED || code == ERR_USER_CANCELED) {
            stop("fingerprint request cancelled");
            return;
        }
        if (code == ERR_HW_UNAVAILABLE || code == ERR_UNABLE_TO_PROCESS
                || code == ERR_TIMEOUT || code == ERR_NO_SPACE || code == ERR_VENDOR) {
            stop("sensor error, press Arm to try again");
        }
    }

    // -------------------------------------------------------------------- misc

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    @SuppressWarnings("deprecation")
    private void logHardwareInfo() {
        if (fingerprintManager == null) {
            log("FingerprintManager unavailable on this build");
            return;
        }
        try {
            log("hardwareDetected=" + fingerprintManager.isHardwareDetected()
                    + " enrolled=" + fingerprintManager.hasEnrolledFingerprints());
        } catch (Throwable t) {
            log("hardware query failed: " + t);
        }
    }

    @SuppressWarnings("deprecation")
    private boolean hasFingerprintHardware() {
        try {
            if (fingerprintManager == null) {
                fingerprintManager =
                        (FingerprintManager) getSystemService(Context.FINGERPRINT_SERVICE);
            }
            if (fingerprintManager == null) return false;
            return fingerprintManager.isHardwareDetected()
                    && fingerprintManager.hasEnrolledFingerprints();
        } catch (Throwable t) {
            log("hardware check failed: " + t);
            return false;
        }
    }

    private void refreshStatus() {
        boolean connected = FpAccessibilityService.isConnected();
        boolean enabled = FpAccessibilityService.isEnabled(this);
        boolean headless = prefs().getBoolean(KEY_HEADLESS, false);

        String accText;
        int accColor;
        if (connected) {
            accText = getString(R.string.status_acc_on);
            accColor = getColor(R.color.ok);
        } else if (enabled) {
            accText = getString(R.string.status_acc_connecting);
            accColor = getColor(R.color.warn);
        } else if (selectedAction != null && selectedAction.appLaunch) {
            accText = getString(R.string.status_acc_not_needed);
            accColor = getColor(R.color.text_dim);
        } else {
            accText = getString(R.string.status_acc_off);
            accColor = getColor(R.color.danger);
        }

        SpannableStringBuilder text = new SpannableStringBuilder();
        appendStatusLine(text, accText, accColor);
        text.append('\n');
        appendStatusLine(text,
                getString(headless ? R.string.status_launch_headless : R.string.status_launch_gui),
                getColor(R.color.accent));
        text.append('\n');
        appendStatusLine(text,
                getString(running ? R.string.status_state_armed : R.string.status_state_idle),
                getColor(running ? R.color.accent : R.color.text_dim));
        statusView.setText(text);

        if (setupView != null) {
            boolean accessibilityNeeded = actionNeedsAccessibility();
            setupView.setVisibility(enabled || !accessibilityNeeded ? View.GONE : View.VISIBLE);
        }
    }

    private void appendStatusLine(SpannableStringBuilder builder, String line, int dotColor) {
        int start = builder.length();
        builder.append("● ");
        builder.setSpan(new ForegroundColorSpan(dotColor), start, start + 1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        builder.append(line);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void log(String message) {
        Log.i(TAG, message);
        String line = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date())
                + "  " + message + "\n";
        String merged = line + logView.getText();
        if (merged.length() > 3200) merged = merged.substring(0, 3200);
        logView.setText(merged);
    }
}
