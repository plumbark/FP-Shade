package com.local.fpshade;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.fingerprint.FingerprintManager;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * Transparent, no-UI entry point meant for the Samsung side-key "Open app"
 * action (double press -> FP Shade Headless).
 *
 * Arms one silent fingerprint session, fires one accessibility action on a
 * match, then finishes. A timeout and an accessibility check keep it from
 * ever hanging around with the sensor armed.
 */
public class HeadlessActivity extends Activity {

    private static final String TAG = "FPShade";
    private static final long ARM_TIMEOUT_MS = 10000;
    private static final long CONNECT_RETRY_MS = 200;
    private static final int CONNECT_RETRIES = 20;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private FingerprintManager fingerprintManager;
    private CancellationSignal cancelSignal;
    private Actions.Action action;
    private int connectTries;
    private boolean closing;
    private boolean armOnResume;

    private final Runnable timeoutRunnable = new Runnable() {
        @Override
        public void run() {
            Log.i(TAG, "headless: timed out waiting for a finger");
            close();
        }
    };

    private final Runnable serviceWaitRunnable = new Runnable() {
        @Override
        public void run() {
            if (FpAccessibilityService.isConnected()) {
                arm();
                return;
            }
            if (connectTries++ >= CONNECT_RETRIES) {
                Log.i(TAG, "headless: service not connected, opening setup");
                openSetup();
                return;
            }
            handler.postDelayed(serviceWaitRunnable, CONNECT_RETRY_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        action = Actions.byId(prefs().getString(MainActivity.KEY_ACTION, "quick_settings"));
        if (action == null) action = Actions.defaultAction();
        // Opening an app is done directly, so it works even without accessibility.
        boolean needsAccessibility = !action.appLaunch;

        if (needsAccessibility && !FpAccessibilityService.isEnabled(this)) {
            Log.i(TAG, "headless: accessibility is off, opening setup");
            openSetup();
            return;
        }
        fingerprintManager =
                (FingerprintManager) getSystemService(FINGERPRINT_SERVICE);
        if (fingerprintManager == null) {
            Log.i(TAG, "headless: no fingerprint service");
            close();
            return;
        }
        try {
            if (!fingerprintManager.isHardwareDetected()
                    || !fingerprintManager.hasEnrolledFingerprints()) {
                Log.i(TAG, "headless: no enrolled fingerprint");
                close();
                return;
            }
        } catch (Throwable t) {
            Log.i(TAG, "headless: hardware query failed: " + t);
            close();
            return;
        }

        if (!needsAccessibility) {
            Log.i(TAG, "headless: launched, arming for app launch");
            // Arm once the window is actually resumed, so launching the target
            // app counts as a foreground activity start.
            armOnResume = true;
            return;
        }
        Log.i(TAG, "headless: launched, waiting for accessibility service");
        connectTries = 0;
        handler.post(serviceWaitRunnable);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (armOnResume) {
            armOnResume = false;
            if (!closing) arm();
        }
    }

    @Override
    protected void onStop() {
        if (!closing) {
            Log.i(TAG, "headless: left foreground before a match, cancelling");
            close();
        }
        super.onStop();
    }

    // ------------------------------------------------------------------ logic

    @SuppressWarnings("deprecation")
    private void arm() {
        cancelSignal = new CancellationSignal();
        try {
            fingerprintManager.authenticate(null, cancelSignal, 0,
                    new FingerprintManager.AuthenticationCallback() {
                        @Override
                        public void onAuthenticationSucceeded(FingerprintManager.AuthenticationResult result) {
                            onMatch();
                        }

                        @Override
                        public void onAuthenticationFailed() {
                            Log.i(TAG, "headless: no match, still listening");
                        }

                        @Override
                        public void onAuthenticationError(int code, CharSequence errString) {
                            Log.i(TAG, "headless: sensor error " + code + ": " + errString);
                            close();
                        }
                    }, handler);
            handler.postDelayed(timeoutRunnable, ARM_TIMEOUT_MS);
            Log.i(TAG, "headless: armed");
        } catch (Throwable t) {
            Log.i(TAG, "headless: authenticate failed: " + t);
            close();
        }
    }

    private void onMatch() {
        if (action != null && action.appLaunch) {
            String flat = prefs().getString(MainActivity.KEY_APP_COMPONENT, null);
            boolean ok = AppLauncher.launch(this, flat);
            Log.i(TAG, "headless: MATCH -> launch_app ok=" + ok);
        } else {
            Actions.Action fireAction = action != null ? action : Actions.defaultAction();
            boolean ok = FpAccessibilityService.fire(fireAction.globalAction);
            Log.i(TAG, "headless: MATCH -> " + fireAction.id + " ok=" + ok);
        }
        close();
    }

    private void openSetup() {
        if (closing) return;
        closing = true;
        try {
            Intent i = new Intent(this, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable t) {
            Log.i(TAG, "headless: cannot open setup: " + t);
        }
        finish();
    }

    private void close() {
        if (closing) return;
        closing = true;
        handler.removeCallbacksAndMessages(null);
        try {
            if (cancelSignal != null) cancelSignal.cancel();
        } catch (Throwable ignored) {
        }
        cancelSignal = null;
        finish();
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
    }
}
