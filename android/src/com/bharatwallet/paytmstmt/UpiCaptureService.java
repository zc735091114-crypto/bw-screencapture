package com.bharatwallet.paytmstmt;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Vector B: capture the account holder's own UPI identifiers from the UPI
 * screen of an already-logged-in wallet app.
 *
 * <p>The tester is already signed in to the wallet on his own phone. He opens
 * the UPI section; this service reads the text of that screen, extracts the
 * addresses, and hands them to {@link OwnnessJudge}, which separates his own
 * identifiers from counterparties. No login, no OTP, no web surface, no
 * statement file.
 *
 * <p>Read-only by construction. The service exposes no click, no text input and
 * no gesture path &mdash; {@code performAction} is never called anywhere in
 * this class or in {@link UpiCapture}, so the pilot physically cannot drive the
 * wallet app. It observes text and nothing else.
 *
 * <p>Scope. Only the wallet packages in {@link #ALLOWED_PACKAGES} are ever
 * read. Any other foreground package is skipped without its text being
 * requested, so the service cannot become a general screen reader.
 *
 * <p>Privacy. Raw screen text is never stored, logged or transmitted. What
 * leaves this class is an address token, a screen class and a count.
 */
public final class UpiCaptureService extends AccessibilityService {

    private static final String TAG = "BwUpiCapture";

    /** The only packages this service will read. */
    public static final Set<String> ALLOWED_PACKAGES = CapturePolicy.WALLET_PACKAGES;

    /** Addresses seen on this service's own harness screen are trusted directly. */
    public static final String HARNESS_PACKAGE = "com.bharatwallet.paytmstmt";

    /** Cap on nodes visited per event, so a huge list cannot stall the UI thread. */
    static final int MAX_NODES = CapturePolicy.MAX_NODES;
    /** Cap on screen text retained per event, in characters. */
    static final int MAX_TEXT = CapturePolicy.MAX_TEXT;

    /** Hops completed on the current walk. Reset whenever capture sees no route. */
    private int routeDone;
    /** True while a walk is in progress, so we do not restart it every event. */
    private boolean routeActive;
    /** Taps performed, for the operator log. */
    private int tapsPerformed;

    /**
     * Walk one hop of the route to the wallet's UPI-management screen.
     *
     * <p>This is the one place in the app that can act on a window rather than
     * only read it, and it is constrained on purpose: see {@link UpiRoute} for
     * why this is a directed walk and not a general tap. It only ever clicks a
     * node whose text matches the current hop label, only inside an allowlisted
     * wallet package, never on a payment screen, and never on an editable
     * field.
     *
     * @return true when a hop was taken.
     */
    private boolean advanceRoute(AccessibilityNodeInfo root, String text) {
        if (root == null || text == null || text.trim().isEmpty()) return false;
        if (!CapturePolicy.isWallet(currentPackage)) return false;
        if (!UpiRoute.routeIsSafe(currentRoute)) return false;

        ScreenClass screen = ScreenClass.classify(text);
        // A payment surface is a hard stop, whatever the route expects.
        if (screen.kind == ScreenClass.Kind.PAY) {
            routeActive = false;
            return false;
        }
        if (UpiRoute.arrived(text, currentRoute)) {
            routeActive = false;
            return true;
        }
        String hop = UpiRoute.nextHop(currentRoute, routeDone, text, screen);
        if (hop == null) return false;

        AccessibilityNodeInfo target = findByLabel(root, hop);
        if (target == null) return false;

        // Never act on an input field, even if one somehow carries the label.
        if (target.isEditable()) {
            safeRecycle(target);
            return false;
        }
        boolean clicked = false;
        try {
            clicked = target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        } catch (Exception ignored) {
            clicked = false;
        }
        safeRecycle(target);
        if (clicked) {
            routeDone++;
            tapsPerformed++;
            Log.i(TAG, "route hop " + routeDone + " of " + currentRoute.size());
        }
        return clicked;
    }

    /** First visible node whose text contains the label, breadth-first, bounded. */
    private AccessibilityNodeInfo findByLabel(AccessibilityNodeInfo root, String label) {
        ArrayList<AccessibilityNodeInfo> queue = new ArrayList<>();
        queue.add(root);
        int visited = 0;
        while (!queue.isEmpty() && visited < MAX_NODES) {
            AccessibilityNodeInfo node = queue.remove(0);
            if (node == null) continue;
            visited++;
            try {
                CharSequence t = node.getText();
                if (t != null && UpiRoute.labelMatches(t.toString(), label)) {
                    return node;
                }
                if (node.isClickable() && UpiRoute.labelMatches(node.getViewIdResourceName(), label)) {
                    return node;
                }
                for (int i = 0; i < node.getChildCount() && queue.size() < MAX_NODES; i++) {
                    queue.add(node.getChild(i));
                }
            } catch (Exception ignored) {
                // Skip a node we cannot read.
            } finally {
                if (node != root) safeRecycle(node);
            }
        }
        return null;
    }

    private static void safeRecycle(AccessibilityNodeInfo node) {
        if (node == null) return;
        try { node.recycle(); } catch (Exception ignored) { }
    }

    private static volatile UpiCaptureService instance;

    private UpiCapture capture;
    private long lastEventAt;
    private int eventsSeen;

    /** Package currently in front, and the route being walked in it. */
    private String currentPackage;
    private List<String> currentRoute = UpiRoute.paytmRoute();

    /** Start a fresh walk to the UPI screen of the wallet the tester named. */
    public void beginRoute(String pkg) {
        List<String> route = UpiRoute.routeFor(pkg);
        if (route == null) {
            Log.w(TAG, "no known route for " + pkg + "; refusing to walk");
            routeActive = false;
            currentPackage = null;
            return;
        }
        currentPackage = pkg;
        currentRoute = route;
        routeDone = 0;
        routeActive = true;
        Log.i(TAG, "route started for " + pkg);
    }

    public int tapsPerformed() { return tapsPerformed; }

    public boolean routeActive() { return routeActive; }

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        capture = new UpiCapture();
        // Ask for window content explicitly. The manifest service is declared
        // without a resource-only config, so the flags are set here instead.
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
            info.notificationTimeout = 0;
            info.eventTypes = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                    | AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    | AccessibilityEvent.TYPE_VIEW_SCROLLED;
            setServiceInfo(info);
        }
        Log.i(TAG, "connected allowlist=" + ALLOWED_PACKAGES.size());
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || capture == null) return;
        String pkg = event.getPackageName() == null
                ? null : event.getPackageName().toString();
        if (pkg == null) return;

        boolean wallet = CapturePolicy.isWallet(pkg);
        boolean harness = HARNESS_PACKAGE.equals(pkg);
        if (!wallet && !harness) return;   // never read any other app

        CharSequence windowText = null;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null) {
            try {
                windowText = root.getText();
            } catch (Exception ignored) {
                // A dead node is not an error; the event text is used instead.
            }
        }
        if (windowText == null || windowText.length() == 0) {
            windowText = joinEventText(event);
        }
        if (windowText == null || windowText.length() == 0) {
            safeRecycle(root);
            return;
        }

        eventsSeen++;
        lastEventAt = System.currentTimeMillis();
        String text = String.valueOf(windowText);
        capture.ingest(wallet ? pkg : HARNESS_PACKAGE, text);
        // Walk the route after reading, while the node tree is still live.
        if (wallet && routeActive && pkg.equals(currentPackage) && root != null) {
            advanceRoute(root, text);
        }
        // Last, because the walk borrows nodes from this tree.
        safeRecycle(root);
    }

    @Override public void onInterrupt() { /* observation only; nothing to interrupt */ }

    /** {@code getText()} yields a list; fold it so a label-only event still reads. */
    static CharSequence joinEventText(AccessibilityEvent event) {
        try {
            java.util.List<CharSequence> parts = event.getText();
            if (parts == null || parts.isEmpty()) return null;
            StringBuilder sb = new StringBuilder();
            for (CharSequence part : parts) {
                if (part == null) continue;
                if (sb.length() > 0) sb.append('\n');
                sb.append(part);
            }
            return sb.length() == 0 ? null : sb;
        } catch (Exception ignored) {
            return null;
        }
    }

    @Override public boolean onUnbind(android.content.Intent intent) {
        if (capture != null) capture.reset();
        return super.onUnbind(intent);
    }

    /** The live service, or null when the tester has not enabled it yet. */
    public static UpiCaptureService instance() { return instance; }

    /** Exposed for the harness and the on-device self-test. */
    public UpiCapture capture() { return capture; }

    public int eventsSeen() { return eventsSeen; }

    public long lastEventAt() { return lastEventAt; }
}