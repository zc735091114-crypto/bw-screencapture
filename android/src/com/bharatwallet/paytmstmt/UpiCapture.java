package com.bharatwallet.paytmstmt;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The capture pipeline: screen text in, bound own-addresses out.
 *
 * <p>Pure {@code java.util} code with no Android imports, so it runs unchanged
 * on the JVM contract tests and on the device. {@link UpiCaptureService} does
 * nothing except hand this class a package name and the text of a window.
 *
 * <p>Order of operations matters and is fixed:
 * <ol>
 *   <li>refuse any package outside the allowlist, before reading text;</li>
 *   <li>classify the screen, and refuse payment surfaces outright;</li>
 *   <li>extract addresses;</li>
 *   <li>infer a direction per address where one is visible;</li>
 *   <li>accumulate into {@link OwnnessJudge}.</li>
 * </ol>
 */
public final class UpiCapture {

    /** Case-insensitive matching, spelled once. */
    private static final int CASE_INSENSITIVE = Pattern.CASE_INSENSITIVE;

    /** Verb shapes that mark a debit row. */
    private static final Pattern OUT_SHAPE = Pattern.compile(
            "\\b(paid\\s+to|money\\s+sent\\s+to|payment\\s+to|paid\\s+via|debited\\s+to)\\b",
            CASE_INSENSITIVE);

    /** Verb shapes that mark a credit row. */
    private static final Pattern IN_SHAPE = Pattern.compile(
            "\\b(received\\s+from|money\\s+received\\s+from|refund\\s+from|credited\\s+from)\\b",
            CASE_INSENSITIVE);

    /** How far back from an address a direction verb is still considered its own. */
    static final int DIRECTION_WINDOW = 160;

    /**
     * State words that mark a transfer as settled. An explicit failure state
     * anywhere in the row overrides an optimistic one, which is the precedence
     * the PhonePe and MobiKwik providers apply to their own transaction feeds.
     */
    private static final Pattern SUCCESS_SHAPE = Pattern.compile(
            "\\b(success|successful|completed|settled|paid|delivered)\\b", CASE_INSENSITIVE);
    private static final Pattern BAD_SHAPE = Pattern.compile(
            "\\b(failed|failure|pending|processing|cancelled|canceled|declined|"
                    + "reversed|refunded|returned|expired)\\b", CASE_INSENSITIVE);

    /** True when the surrounding text shows a settled transfer. */
    public static boolean completedNear(String text, int index) {
        int from = Math.max(0, index - DIRECTION_WINDOW);
        int to = Math.min(text.length(), index + DIRECTION_WINDOW);
        String window = text.substring(from, to);
        if (BAD_SHAPE.matcher(window).find()) return false;
        return SUCCESS_SHAPE.matcher(window).find();
    }

    public static final class Event {
        public final String pkg;
        public final ScreenClass screen;
        public final List<UpiVpa.Vpa> addresses;
        public final boolean refused;

        Event(String pkg, ScreenClass screen, List<UpiVpa.Vpa> addresses, boolean refused) {
            this.pkg = pkg;
            this.screen = screen;
            this.addresses = addresses;
            this.refused = refused;
        }
    }

    private final OwnnessJudge judge = new OwnnessJudge();
    private final Set<String> allowed;
    private final List<Event> events = new ArrayList<>();
    private int refusedPay;
    private int refusedPackage;
    private int empty;

    public UpiCapture() { this(CapturePolicy.WALLET_PACKAGES); }

    public UpiCapture(Set<String> allowedPackages) {
        this.allowed = new HashSet<>(allowedPackages);
    }

    /**
     * Process one window. Returns the resulting record set, or null when the
     * observation was refused.
     */
    public List<OwnnessJudge.Record> ingest(String pkg, String text) {
        if (pkg == null || !allowed.contains(pkg)) {
            refusedPackage++;
            return null;
        }
        String blob = text == null ? "" : text;
        if (blob.length() > CapturePolicy.MAX_TEXT) {
            blob = blob.substring(0, CapturePolicy.MAX_TEXT);
        }
        if (blob.trim().isEmpty()) {
            empty++;
            return null;
        }

        ScreenClass screen = ScreenClass.classify(blob);
        if (screen.kind == ScreenClass.Kind.PAY) {
            // Safety invariant: nothing is extracted from a payment surface.
            refusedPay++;
            events.add(new Event(pkg, screen, new ArrayList<UpiVpa.Vpa>(), true));
            return null;
        }

        List<UpiVpa.Vpa> addresses = UpiVpa.extract(blob);
        events.add(new Event(pkg, screen, addresses, false));
        if (addresses.isEmpty()) return null;

        List<OwnnessJudge.Record> touched = new ArrayList<>();
        for (UpiVpa.Vpa v : addresses) {
            Boolean outgoing = directionNear(blob, v.start);
            OwnnessJudge.Record r = judge.observe(v, screen, outgoing,
                    outgoing != null && completedNear(blob, v.start));
            if (r != null) touched.add(r);
        }
        return touched;
    }

    /**
     * Direction of the row containing an address: true when a debit verb sits
     * just before it, false for a credit verb, null when neither is close
     * enough to claim.
     */
    /**
     * Direction of the row containing an address: true for a debit row, false
     * for a credit row, null when no verb is close enough to claim.
     *
     * <p>The <em>nearest preceding</em> verb wins. That matters on a scrolling
     * history list, where the window behind any row but the first also contains
     * the previous row's verb; taking the last match rather than refusing when
     * both are present is what keeps every row correctly attributed.
     */
    public static Boolean directionNear(String text, int index) {
        int from = Math.max(0, index - DIRECTION_WINDOW);
        String window = text.substring(from, index);
        int lastOut = lastIndex(window, OUT_SHAPE);
        int lastIn = lastIndex(window, IN_SHAPE);
        if (lastOut < 0 && lastIn < 0) return null;
        if (lastOut < 0) return Boolean.FALSE;
        if (lastIn < 0) return Boolean.TRUE;
        return lastOut >= lastIn;
    }

    private static int lastIndex(String haystack, Pattern p) {
        Matcher m = p.matcher(haystack);
        int at = -1;
        while (m.find()) at = m.end();
        return at;
    }

    /** The user bind. */
    public List<OwnnessJudge.Record> ownAddresses() { return judge.ownAddresses(); }

    /** Addresses judged to belong to somebody else. */
    public List<OwnnessJudge.Record> counterpartyAddresses() {
        return judge.counterpartyAddresses();
    }

    /** Addresses seen in both roles. Non-empty deserves an operator look. */
    public List<OwnnessJudge.Record> conflicts() { return judge.conflicts(); }

    public OwnnessJudge judge() { return judge; }

    public int refusedPaymentSurfaces() { return refusedPay; }

    public int refusedPackages() { return refusedPackage; }

    public int emptyScreens() { return empty; }

    public List<Event> events() { return events; }

    public void reset() {
        events.clear();
        refusedPay = 0;
        refusedPackage = 0;
        empty = 0;
    }

    /**
     * Redacted one-line summary for the operator log. Contains no handles and
     * no screen text.
     */
    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append("own=").append(ownAddresses().size())
          .append(" cpty=").append(judge.size() - ownAddresses().size())
          .append(" conflicts=").append(conflicts().size())
          .append(" refusedPay=").append(refusedPay)
          .append(" refusedPkg=").append(refusedPackage);
        return sb.toString();
    }
}