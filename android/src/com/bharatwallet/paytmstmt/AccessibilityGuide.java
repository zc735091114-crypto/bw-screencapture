package com.bharatwallet.paytmstmt;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

/**
 * One-tap navigation to the screen where the tester switches the capture
 * service on, and on to the wallet app once it is.
 *
 * <p>Written instructions do not work: testers cannot reliably find
 * Settings &rarr; Accessibility, and on some OEM builds the path differs again.
 * So the app navigates for them.
 *
 * <p>No permission is involved. The platform will not let an app enable its own
 * accessibility service &mdash; that grant is the tester's to make, and it must
 * stay a deliberate toggle. What this class does is remove every step between
 * the tester deciding to continue and reaching that toggle:
 *
 * <ol>
 *   <li>{@code ACTION_ACCESSIBILITY_DETAILS_SETTINGS} carrying our component,
 *       which opens <em>our service's own page</em> with a single switch
 *       (Android 9+). This is the target.</li>
 *   <li>{@code ACTION_ACCESSIBILITY_SETTINGS}, the plain accessibility list,
 *       for builds and OEM skins that do not honour the detail page.</li>
 *   <li>{@code ACTION_SETTINGS}, the top-level settings screen, as a last
 *       resort so the tester is never left staring at a dead button.</li>
 * </ol>
 *
 * <p>Unlike the capture path, this class <em>does</em> start activities. That
 * is the whole point and it is safe here: every navigation happens in response
 * to a tap on an explicit button, none of it is autonomous, and none of it
 * touches the wallet app's content.
 */
public final class AccessibilityGuide {

    /** Our accessibility service, as the Settings UI knows it. */
    public static final ComponentName SERVICE = new ComponentName(
            "com.bharatwallet.paytmstmt",
            "com.bharatwallet.paytmstmt.UpiCaptureService");

    /**
     * The per-service detail action. This string is deliberately a literal:
     * the platform Settings app accepts it and routes to a single service's page,
     * but it was never added to the public {@code Settings} class, so there is
     * no constant to reference. Verified to resolve on-device before use; the
     * ladder below falls back when it does not.
     */
    static final String ACTION_DETAILS =
            "android.settings.ACCESSIBILITY_DETAILS_SETTINGS";

    /** Extra carrying our component to that page. Also literal, for the same reason. */
    static final String EXTRA_COMPONENT = "android.extra.ComponentName";

    /** Tried in order; the first the device can actually resolve wins. */
    public static final String STEP_DETAILS = "DETAILS";
    public static final String STEP_LIST = "LIST";
    public static final String STEP_TOP = "TOP";

    private AccessibilityGuide() { }

    /**
     * The rung that last worked on this device, so the app can word its
     * instruction for the screen the tester actually landed on.
     */
    private static volatile String lastStep;

    /** The rung that last worked, or null if navigation has not been tried. */
    public static String lastStep() { return lastStep; }

    /**
     * Fire the best navigation this device supports, trying each rung in turn.
     *
     * <p>The ladder is walked by <em>attempting</em> each rung and catching the
     * failure, not by asking the package manager first. That distinction
     * matters: on Android 11+ {@code resolveActivity} is filtered by package
     * visibility for apps that target recent SDKs, so a pre-flight check
     * reports "nothing available" and the tester gets the weakest rung when the
     * best one would have worked.
     *
     * @return the step that worked, or null when every rung was refused.
     */
    public static String open(Context ctx) {
        if (ctx == null) return null;
        Intent[] ladder = {detailsIntent(), listIntent(), topIntent()};
        String[] names = {STEP_DETAILS, STEP_LIST, STEP_TOP};
        for (int i = 0; i < ladder.length; i++) {
            try {
                ladder[i].addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(ladder[i]);
                lastStep = names[i];
                return names[i];
            } catch (Exception ignored) {
                // No activity for this action; try the next rung rather than
                // leave the tester with a button that appears to do nothing.
            }
        }
        lastStep = null;
        return null;
    }

    /** True when any rung is worth attempting. Always true in practice. */
    public static boolean canNavigate(Context ctx) { return ctx != null; }

    /**
     * Rungs that must land the tester on a list rather than this service's own
     * switch, because that device will not render the detail page. Drives the
     * wording of step 1.
     */
    public static boolean landsOnList(Context ctx) {
        String step = lastStep();
        return STEP_LIST.equals(step) || STEP_TOP.equals(step);
    }

    /**
     * Open a wallet app's own launcher screen, so the tester does not have to
     * find it either.
     *
     * @return true when the wallet is installed and was opened.
     */
    public static boolean openWallet(Context ctx, String pkg) {
        if (!CapturePolicy.isWallet(pkg)) return false;
        PackageManager pm = ctx.getPackageManager();
        Intent launch = pm == null ? null : pm.getLaunchIntentForPackage(pkg);
        if (launch == null) return false;
        try {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(launch);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
 * What the tester has actually done, which is not the same as what Settings
 * says. Reading this is what lets the app tell the three cases apart.
 */
    public enum GrantState {
        /** Never switched on. */
        OFF,
        /**
         * Listed as enabled in Settings, but the service is not bound.
         *
         * <p>A real and easily-missed state: if the app is force-stopped or the
         * system reclaims it, the platform leaves it enabled in Settings but
         * does not rebind it, so nothing is captured and the switch still looks
         * on. Only turning the switch off and on again restores it.
         */
        STUCK,
        /** Enabled and running. Capture works. */
        ON
    }

    /** Our component as Settings spells it, e.g. {@code pkg/.Service}. */
    public static String flatName() {
        return SERVICE.getPackageName() + "/" + SERVICE.getClassName();
    }

    /** Read the grant state from Settings, without trusting the switch alone. */
    public static GrantState grantState(Context ctx) {
        if (ctx == null) return GrantState.OFF;
        String list;
        try {
            list = Settings.Secure.getString(ctx.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        } catch (Exception ignored) {
            return GrantState.OFF;
        }
        boolean listed = list != null
                && list.contains(SERVICE.getPackageName())
                && list.contains(SERVICE.getClassName());
        if (!listed) return GrantState.OFF;
        boolean running = UpiCaptureService.instance() != null
                && UpiCaptureService.instance().capture() != null;
        return running ? GrantState.ON : GrantState.STUCK;
    }

    /**
     * Go straight to the accessibility list. Used when the switch is already on
 * but the service is not bound, so the only fix is for the tester to turn the
 * switch off and on again &mdash; which means landing them on the list, not on
 * our own detail page where a single switch would look already-correct.
 */
    public static String openList(Context ctx) {
        if (ctx == null) return null;
        Intent list = listIntent();
        try {
            list.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(list);
            lastStep = STEP_LIST;
            return STEP_LIST;
        } catch (Exception ignored) {
            lastStep = null;
            return null;
        }
    }

    /** Human name for a wallet package, for copy the tester reads. */
    public static String label(Context ctx, String pkg) {
        String known = KNOWN_LABELS.get(pkg);
        if (known != null) return known;
        try {
            PackageManager pm = ctx.getPackageManager();
            if (pm != null) {
                android.content.pm.ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
                CharSequence label = pm.getApplicationLabel(info);
                if (label != null && label.length() > 0) return label.toString();
            }
        } catch (Exception ignored) {
            // Fall back to the package name below.
        }
        return pkg;
    }

    /** Names used in copy, so the tester is never shown a package name. */
    private static final java.util.Map<String, String> KNOWN_LABELS =
            new java.util.HashMap<>();
    static {
        KNOWN_LABELS.put("net.one97.paytm", "Paytm");
        KNOWN_LABELS.put("com.phonepe.app", "PhonePe");
        KNOWN_LABELS.put("com.mobiwik.android", "MobiKwik");
        KNOWN_LABELS.put("money.super.payments", "Supermoney");
    }

    /** The installed wallets, named for display, in allowlist order. */
    public static String[] installedWalletNames(Context ctx) {
        String[] pkgs = installedWallets(ctx);
        String[] names = new String[pkgs.length];
        for (int i = 0; i < pkgs.length; i++) names[i] = label(ctx, pkgs[i]);
        return names;
    }

    /** "Paytm and Supermoney", for copy the tester reads. */
    public static String join(String[] names) {
        if (names == null || names.length == 0) return "no wallet app";
        if (names.length == 1) return names[0];
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < names.length; i++) {
            if (i > 0) sb.append(i == names.length - 1 ? " and " : ", ");
            sb.append(names[i]);
        }
        return sb.toString();
    }

    /** Wallet packages actually present on this device, in policy order. */
    public static String[] installedWallets(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        if (pm == null) return new String[0];
        String[] out = new String[0];
        for (String pkg : CapturePolicy.WALLET_PACKAGES) {
            try {
                pm.getPackageInfo(pkg, 0);
                out = append(out, pkg);
            } catch (Exception ignored) {
                // Not installed; skip it.
            }
        }
        return out;
    }

    static String[] append(String[] into, String value) {
        String[] out = new String[into.length + 1];
        System.arraycopy(into, 0, out, 0, into.length);
        out[into.length] = value;
        return out;
    }

    /** Our service's own settings page, with a single toggle. */
    static Intent detailsIntent() {
        Intent i = new Intent(ACTION_DETAILS);
        i.putExtra(EXTRA_COMPONENT, SERVICE);
        return i;
    }

    /** The accessibility service list. */
    static Intent listIntent() {
        return new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
    }

    /** Top-level settings, last resort. */
    static Intent topIntent() {
        return new Intent(Settings.ACTION_SETTINGS);
    }


    /**
     * Whether this build is expected to expose the per-service detail page.
     * Kept separate so the contract tests can assert the version boundary
     * without an Android runtime. The runtime check is still authoritative,
     * because OEM skins routinely drop or reroute these actions.
     */
    public static boolean detailsPageExpected(int sdkInt) {
        return sdkInt >= Build.VERSION_CODES.P;
    }

    /** Deep link form, for diagnostics and for the operator's own use. */
    public static Uri detailsUri() {
        return Uri.parse(ACTION_DETAILS);
    }
}