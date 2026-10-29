package com.local.fpshade;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.provider.Settings;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

/**
 * The only thing this service does is fire one global action on request.
 * It never reads window content and keeps no state.
 */
public class FpAccessibilityService extends AccessibilityService {

    private static volatile FpAccessibilityService instance;

    public static boolean isConnected() {
        return instance != null;
    }

    /** Reads the system accessibility list, independent of connection state. */
    public static boolean isEnabled(Context context) {
        try {
            String enabled = Settings.Secure.getString(context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabled == null) return false;
            String me = context.getPackageName() + "/"
                    + FpAccessibilityService.class.getName();
            String meShort = context.getPackageName() + "/.FpAccessibilityService";
            return enabled.contains(me) || enabled.contains(meShort);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * @param globalAction one of the AccessibilityService.GLOBAL_ACTION_* values
     * @return true if the action was requested successfully
     */
    public static boolean fire(int globalAction) {
        FpAccessibilityService svc = instance;
        if (svc == null) return false;
        try {
            return svc.performGlobalAction(globalAction);
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    protected void onServiceConnected() {
        instance = this;
        Log.i("FPShade", "accessibility service connected");
    }

    @Override
    public void onDestroy() {
        // Only clear if this is still the live instance: after an app update the
        // old service can be destroyed *after* the new one connects.
        if (instance == this) instance = null;
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // intentionally empty
    }

    @Override
    public void onInterrupt() {
        // intentionally empty
    }
}
