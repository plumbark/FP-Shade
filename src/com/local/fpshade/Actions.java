package com.local.fpshade;

import android.accessibilityservice.AccessibilityService;
import android.os.Build;

import java.util.ArrayList;
import java.util.List;

/**
 * The catalog of global actions that can be triggered on a fingerprint match.
 * Only actions supported by the running Android version are offered.
 */
public final class Actions {

    public static final class Action {
        public final String id;
        public final int labelRes;
        public final int descRes;
        public final int sectionRes;
        public final int globalAction;
        public final int minSdk;
        /** True when this action launches a user-chosen app instead of a global action. */
        public final boolean appLaunch;

        Action(String id, int labelRes, int descRes, int sectionRes,
               int globalAction, int minSdk) {
            this(id, labelRes, descRes, sectionRes, globalAction, minSdk, false);
        }

        Action(String id, int labelRes, int descRes, int sectionRes,
               int globalAction, int minSdk, boolean appLaunch) {
            this.id = id;
            this.labelRes = labelRes;
            this.descRes = descRes;
            this.sectionRes = sectionRes;
            this.globalAction = globalAction;
            this.minSdk = minSdk;
            this.appLaunch = appLaunch;
        }
    }

    private static final Action[] ALL = new Action[]{
            new Action("notifications",
                    R.string.action_notifications, R.string.action_notifications_desc,
                    R.string.section_panels,
                    AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS, 16),
            new Action("quick_settings",
                    R.string.action_quick_settings, R.string.action_quick_settings_desc,
                    R.string.section_panels,
                    AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS, 17),
            new Action("dismiss_shade",
                    R.string.action_dismiss_shade, R.string.action_dismiss_shade_desc,
                    R.string.section_panels,
                    AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE, 31),

            new Action("lock_screen",
                    R.string.action_lock_screen, R.string.action_lock_screen_desc,
                    R.string.section_device,
                    AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN, 28),
            new Action("power_menu",
                    R.string.action_power_menu, R.string.action_power_menu_desc,
                    R.string.section_device,
                    AccessibilityService.GLOBAL_ACTION_POWER_DIALOG, 21),
            new Action("screenshot",
                    R.string.action_screenshot, R.string.action_screenshot_desc,
                    R.string.section_device,
                    AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT, 28),
            new Action("split_screen",
                    R.string.action_split_screen, R.string.action_split_screen_desc,
                    R.string.section_device,
                    AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN, 24),

            new Action("back",
                    R.string.action_back, R.string.action_back_desc,
                    R.string.section_navigation,
                    AccessibilityService.GLOBAL_ACTION_BACK, 16),
            new Action("home",
                    R.string.action_home, R.string.action_home_desc,
                    R.string.section_navigation,
                    AccessibilityService.GLOBAL_ACTION_HOME, 16),
            new Action("recents",
                    R.string.action_recents, R.string.action_recents_desc,
                    R.string.section_navigation,
                    AccessibilityService.GLOBAL_ACTION_RECENTS, 16),

            new Action("media_play_pause",
                    R.string.action_media, R.string.action_media_desc,
                    R.string.section_media,
                    AccessibilityService.GLOBAL_ACTION_KEYCODE_HEADSETHOOK, 31),

            new Action("launch_app",
                    R.string.action_launch_app, R.string.action_launch_app_desc,
                    R.string.section_apps,
                    0, 16, true),
    };

    private Actions() {
    }

    public static List<Action> available() {
        List<Action> out = new ArrayList<>();
        for (Action a : ALL) {
            if (Build.VERSION.SDK_INT >= a.minSdk) out.add(a);
        }
        return out;
    }

    public static Action byId(String id) {
        if (id == null) return null;
        for (Action a : ALL) {
            if (a.id.equals(id)) return a;
        }
        return null;
    }

    public static Action defaultAction() {
        Action quick = byId("quick_settings");
        return quick != null ? quick : ALL[0];
    }
}
