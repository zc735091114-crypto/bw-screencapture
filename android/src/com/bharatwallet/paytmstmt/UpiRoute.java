package com.bharatwallet.paytmstmt;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The route to a wallet's own UPI-management screen, as a fixed list of hops.
 *
 * <p>Paytm has no deep link to this screen: its only exported UPI activities
 * are {@code UPIDeeplinkActivity} and {@code UpiMandateDeepLinkActivity}, and
 * both are guarded by {@code net.one97.paytm.permission.ACTIVITY_OPEN_UPI_OCA},
 * a signature-level permission an outside app cannot hold. The path is
 * Home &rarr; UPI &amp; Payment settings &rarr; Manage UPI ID, which means an
 * in-app tap.
 *
 * <p>This class is deliberately not a "tap anything" capability. It is a
 * <b>directed walk down one known route</b>:
 *
 * <ul>
 *   <li>Each hop names a label that must be present. If it is absent, the walk
 *       stops rather than trying alternatives.</li>
 *   <li>A hop label containing any word from {@link #FORBIDDEN_TAP} is rejected
 *       outright, so a route can never be pointed at a payment control even by
 *       a future edit to the hop list.</li>
 *   <li>Every hop is re-checked against the screen classification after it runs.
 *       Reaching a {@link ScreenClass.Kind#PAY} screen aborts the walk
 *       immediately.</li>
 * </ul>
 *
 * <p>That is a narrower grant than a free tap, and the build gate was widened to
 * match rather than quietly bypassed: {@code build.py} now expects the walk in
 * exactly one file and nowhere else.
 */
public final class UpiRoute {

    /** The route the tester described, in order, as label fragments. */
    public static final List<String> PAYTM_UPI_ROUTE = Collections.unmodifiableList(
            Arrays.asList(
                    "UPI & Payment",     // Home → UPI & Payment settings
                    "Manage UPI ID"     // → Manage UPI ID
            ));

    /**
     * The route for {@code money.super.payments}. Supermoney exposes its UPI
     * management under "Manage UPI", reached from the home "UPI" entry.
     */
    public static final List<String> SUPERMONEY_UPI_ROUTE = Collections.unmodifiableList(
            Arrays.asList(
                    "UPI",               // Home → UPI
                    "Manage UPI"         // → Manage UPI
            ));

    /**
     * Which wallet's route should be walked?
     *
     * <p>Package-specific, and closed: an unknown wallet returns null so the
     * walk refuses to start rather than silently applying Paytm's labels to a
     * different app's screen. That is what was happening before — the service
     * always used the Paytm route, so a Supermoney screen produced no hops and
     * looked like a silent failure instead of a detected-but-unsupported app.
     */
    public static List<String> routeFor(String pkg) {
        if (pkg == null) return null;
        switch (pkg) {
            case "net.one97.paytm":       return PAYTM_UPI_ROUTE;
            case "money.super.payments":  return SUPERMONEY_UPI_ROUTE;
            default:                      return null;
        }
    }

    /**
     * Words that make a tap unacceptable wherever they appear. Payment verbs
     * and amount entry are the ones that could move money, so a hop label
     * containing any of these is refused even if someone adds it to the route.
     */
    public static final List<String> FORBIDDEN_TAP = Collections.unmodifiableList(
            Arrays.asList(
                    "pay now", "pay ", "send money", "send amount", "transfer",
                    "collect", "request money", "add money", "withdraw",
                    "amount", "upi pin", "mpin", "merchant pin", "confirm",
                    "proceed", "checkout", "bill", "recharge"
            ));

    /** Case-insensitive fragment match, as a screen would present it. */
    public static boolean labelMatches(String screenText, String label) {
        if (screenText == null || label == null) return false;
        String hay = screenText.toLowerCase(Locale.US);
        return hay.contains(label.toLowerCase(Locale.US));
    }

    /**
     * Is this hop label safe to tap?
     *
     * @return null when safe, otherwise the word that disqualified it.
     */
    public static String forbiddenWord(String label) {
        if (label == null) return null;
        String low = label.toLowerCase(Locale.US);
        for (String bad : FORBIDDEN_TAP) {
            if (low.contains(bad)) return bad;
        }
        return null;
    }

    /** The next hop to attempt, or null when the route is finished or blocked. */
    public static String nextHop(List<String> route, int done, String screenText,
                                 ScreenClass screen) {
        if (route == null || screen == null) return null;
        // Safety: never navigate from a payment surface, whatever the route says.
        if (screen.kind == ScreenClass.Kind.PAY) return null;
        // Fail closed on the whole route, not just the current hop. Skipping a
        // forbidden hop would let the walk continue past it while the hop
        // counter stayed aligned with the wrong entry.
        if (!routeIsSafe(route)) return null;
        for (int i = Math.max(0, done); i < route.size(); i++) {
            String hop = route.get(i);
            if (!labelMatches(screenText, hop)) continue;   // not here yet
            return hop;
        }
        return null;
    }

    /** True when the target screen has been reached. */
    public static boolean arrived(String screenText, List<String> route) {
        if (screenText == null || route == null || route.isEmpty()) return false;
        String last = route.get(route.size() - 1);
        return forbiddenWord(last) == null && labelMatches(screenText, last);
    }

    /**
     * The whole route, screened. Every hop must pass, so a single bad entry
     * disables navigation rather than silently skipping it.
     */
    public static boolean routeIsSafe(List<String> route) {
        if (route == null || route.isEmpty()) return false;
        for (String hop : route) {
            if (hop == null || hop.trim().isEmpty()) return false;
            if (forbiddenWord(hop) != null) return false;
        }
        return true;
    }

    /** The default route, screened. */
    public static List<String> paytmRoute() {
        List<String> route = PAYTM_UPI_ROUTE;
        if (!routeIsSafe(route)) {
            throw new IllegalStateException("Paytm UPI route contains an unsafe hop");
        }
        return new ArrayList<>(route);
    }
}