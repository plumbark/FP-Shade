package com.local.fpshade;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds launchable apps and launches a chosen one. Kept separate from the GUI
 * so the headless path can launch without touching any UI.
 */
final class AppLauncher {

    private AppLauncher() {
    }

    static final class Entry {
        final String flat;
        final String label;
        final String pkg;
        final ResolveInfo info;

        Entry(String flat, String label, String pkg, ResolveInfo info) {
            this.flat = flat;
            this.label = label;
            this.pkg = pkg;
            this.info = info;
        }
    }

    /** All user-launchable activities, alphabetically, without this app itself. */
    static List<Entry> launchable(Context context) {
        List<Entry> out = new ArrayList<>();
        try {
            PackageManager pm = context.getPackageManager();
            Intent main = new Intent(Intent.ACTION_MAIN);
            main.addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> infos = pm.queryIntentActivities(main, 0);
            Map<String, Entry> seen = new LinkedHashMap<>();
            for (ResolveInfo info : infos) {
                if (info == null || info.activityInfo == null) continue;
                if (context.getPackageName().equals(info.activityInfo.packageName)) continue;
                ComponentName cn = new ComponentName(
                        info.activityInfo.packageName, info.activityInfo.name);
                String flat = cn.flattenToString();
                if (seen.containsKey(flat)) continue;
                CharSequence label = info.loadLabel(pm);
                seen.put(flat, new Entry(flat,
                        label == null ? info.activityInfo.packageName : label.toString(),
                        info.activityInfo.packageName, info));
            }
            out.addAll(seen.values());
            Collections.sort(out, new Comparator<Entry>() {
                @Override
                public int compare(Entry a, Entry b) {
                    return a.label.compareToIgnoreCase(b.label);
                }
            });
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** @return true if an activity was asked to start. */
    static boolean launch(Context context, String flat) {
        ComponentName cn = flat == null ? null : ComponentName.unflattenFromString(flat);
        if (cn == null) return false;
        try {
            Intent intent = Intent.makeMainActivity(cn);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            context.startActivity(intent);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Best-effort readable name for a stored component. */
    static String label(Context context, String flat) {
        ComponentName cn = flat == null ? null : ComponentName.unflattenFromString(flat);
        if (cn == null) return null;
        try {
            PackageManager pm = context.getPackageManager();
            return pm.getActivityInfo(cn, 0).loadLabel(pm).toString();
        } catch (Throwable t) {
            return null;
        }
    }
}
