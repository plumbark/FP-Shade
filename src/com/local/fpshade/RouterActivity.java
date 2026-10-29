package com.local.fpshade;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * Invisible launcher entry. Decides where an app launch should go:
 *
 *  - headless toggle ON  + accessibility enabled -> HeadlessActivity (no UI)
 *  - headless toggle ON  + accessibility missing -> setup GUI
 *  - headless toggle OFF                         -> setup GUI
 *
 * Long-press the icon -> "FP Shade settings" shortcut always opens the GUI.
 */
public class RouterActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        boolean headless = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
                .getBoolean(MainActivity.KEY_HEADLESS, false);
        boolean accessibilityOn = FpAccessibilityService.isEnabled(this);
        Actions.Action action = Actions.byId(getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
                .getString(MainActivity.KEY_ACTION, null));
        // Launching an app needs the sensor only, not the accessibility service.
        boolean accessibilityNeeded = action == null || !action.appLaunch;

        Intent next;
        if (headless && (accessibilityOn || !accessibilityNeeded)) {
            // Separate task so the GUI task can be removed without taking the
            // headless window down with it. No animation = no flash.
            next = new Intent(this, HeadlessActivity.class);
            next.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            try {
                startActivity(next);
            } catch (Throwable ignored) {
            }
            // If the GUI was left in this task, it would be visible through the
            // transparent headless window. Remove the whole task behind it.
            finishAndRemoveTask();
            return;
        }

        next = new Intent(this, MainActivity.class);
        next.putExtra(MainActivity.EXTRA_SETTINGS, true);
        next.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(next);
        } catch (Throwable ignored) {
        }
        finish();
    }
}
