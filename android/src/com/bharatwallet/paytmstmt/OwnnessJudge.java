package com.bharatwallet.paytmstmt;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Decides whether a captured address is the account holder's <em>own</em> UPI
 * ID or somebody else's. This is the user-bind capability.
 *
 * <p>Evidence is accumulated across screens, because no single screen is
 * sufficient on its own:
 *
 * <ul>
 *   <li>{@code OWN_MANAGE_SURFACE} &mdash; the address appeared on the UPI
 *       management screen, whose entire purpose is listing the account's own
 *       identifiers. Strongest single signal.</li>
 *   <li>{@code OWN_SELF_TRANSFER} &mdash; the same address was seen on both
 *       sides of a transfer, which is what moving money between two of your own
 *       accounts looks like.</li>
 *   <li>{@code COUNTERPARTY_ON_HISTORY} &mdash; the address sat in a
 *       transaction row on the history screen.</li>
 *   <li>{@code COUNTERPARTY_ON_STATEMENT} &mdash; ditto for a statement export.</li>
 *   <li>{@code UNKNOWN_SURFACE} &mdash; seen somewhere unrecognised, so no
 *       claim is made either way.</li>
 * </ul>
 *
 * <p>Payment surfaces are not evidence of anything: observations recorded
 * while a {@link ScreenClass.Kind#PAY} screen was in front are discarded
 * before they reach the store, so a checkout screen can neither establish nor
 * clear a bind.
 *
 * <p>Nothing is ever promoted to {@code OWN} without a positive signal. An
 * address seen only in history stays {@code COUNTERPARTY}, and an address seen
 * only on an unrecognised surface stays {@code UNKNOWN}.
 */
public final class OwnnessJudge {

    public enum Verdict { OWN, COUNTERPARTY, UNKNOWN }

    /** Stable reason codes. These are the only strings written to the log. */
    public static final String R_OWN_MANAGE = "OWN_MANAGE_SURFACE";
    public static final String R_OWN_SELF_XFER = "OWN_SELF_TRANSFER";
    public static final String R_CPTY_HISTORY = "COUNTERPARTY_ON_HISTORY";
    public static final String R_CPTY_STATEMENT = "COUNTERPARTY_ON_STATEMENT";
    public static final String R_UNKNOWN_SURFACE = "UNKNOWN_SURFACE";

    /** Where an address has been seen, and how. */
    public static final class Record {
        public final UpiVpa.Vpa vpa;
        public Verdict verdict = Verdict.UNKNOWN;
        public String reason = R_UNKNOWN_SURFACE;
        /** Roles the address has played; a conflict is surfaced, not hidden. */
        public final Set<String> roles = new LinkedHashSet<>();
        /** Screens on which the account's own identifiers were listed. */
        public int ownSurfaceHits;
        public int counterpartySurfaceHits;
        public int unknownSurfaceHits;
        /** Direction evidence for self-transfer detection. */
        boolean sawOutgoing;
        boolean sawIncoming;
        /**
         * Whether a transaction-bearing surface has actually been read. Tracked
         * as its own flag rather than inferred from {@link #roles}, because a
         * role is recorded with its direction ("UPI_HISTORY:OUT") and an exact
         * name lookup would silently miss it.
         */
        boolean historySeen;
        boolean statementSeen;
        /** Times seen in a transfer row at all, successfully or not. */
        public int usageCount;
        /** Times seen in a row that carried a completed transfer. */
        public int successfulCount;

        Record(UpiVpa.Vpa vpa) { this.vpa = vpa; }

        public boolean conflict() { return ownSurfaceHits > 0 && counterpartySurfaceHits > 0; }

        /**
         * Whether history has actually been read yet. Until it has, "no usage"
         * means unknown rather than never used &mdash; the distinction PhonePe's
         * provider draws between {@code NO_HISTORY} and {@code UNKNOWN}.
         */
        public boolean historyRead() {
            return historySeen || statementSeen;
        }

        public String usageStatus() {
            if (!historyRead()) return "UNKNOWN";
            return usageCount > 0 ? "COMMON" : "NO_HISTORY";
        }

        /**
         * True when this address has been seen moving money. An address that is
         * listed but never used is still the customer's own, but it is the
         * weaker of the two claims and is flagged so the operator can see it.
         */
        public boolean requiresConfirmation() { return usageCount == 0; }

        /** Redacted; handles never appear in a log line. */
        @Override public String toString() {
            return UpiVpa.logLabel(vpa) + " " + verdict + "/" + reason
                    + " own=" + ownSurfaceHits + " cpty=" + counterpartySurfaceHits
                    + " use=" + usageCount + "/" + usageStatus()
                    + (conflict() ? " CONFLICT" : "");
        }
    }

    private final Map<String, Record> byKey = new HashMap<>();

    /**
     * Feed one screen observation.
     *
     * @param outgoing  true when the address appeared in a debit row, false in a
     *                  credit row, and null when the direction is unknown (a
     *                  management screen has no direction).
     * @param completed true when the row showed a settled transfer, false when
     *                  it showed a pending or failed one.
     * @return the updated record, or null when the observation was refused
     */
    public Record observe(UpiVpa.Vpa vpa, ScreenClass screen, Boolean outgoing) {
        return observe(vpa, screen, outgoing, false);
    }

    public Record observe(UpiVpa.Vpa vpa, ScreenClass screen, Boolean outgoing,
                          boolean completed) {
        if (vpa == null || screen == null) return null;
        // Safety invariant: a payment screen contributes nothing.
        if (screen.kind == ScreenClass.Kind.PAY) return null;

        Record r = byKey.get(vpa.key());
        if (r == null) {
            r = new Record(vpa);
            byKey.put(vpa.key(), r);
        }
        if (outgoing != null) {
            if (outgoing) r.sawOutgoing = true; else r.sawIncoming = true;
            r.usageCount++;
            if (completed) r.successfulCount++;
        }
        r.roles.add(screen.kind.name() + (outgoing == null ? "" : (outgoing ? ":OUT" : ":IN")));

        switch (screen.kind) {
            case UPI_MANAGE:  r.ownSurfaceHits++;  break;
            case UPI_HISTORY: r.counterpartySurfaceHits++; r.historySeen = true; break;
            case STATEMENT:   r.counterpartySurfaceHits++; r.statementSeen = true; break;
            default:          r.unknownSurfaceHits++; break;
        }
        resolve(r);
        return r;
    }

    /** Recompute a record's verdict from its accumulated evidence. */
    void resolve(Record r) {
        if (r.ownSurfaceHits > 0) {
            r.verdict = Verdict.OWN;
            r.reason = R_OWN_MANAGE;
            return;
        }
        if (r.sawOutgoing && r.sawIncoming) {
            r.verdict = Verdict.OWN;
            r.reason = R_OWN_SELF_XFER;
            return;
        }
        if (r.counterpartySurfaceHits > 0) {
            r.verdict = Verdict.COUNTERPARTY;
            r.reason = r.statementSeen ? R_CPTY_STATEMENT : R_CPTY_HISTORY;
            return;
        }
        r.verdict = Verdict.UNKNOWN;
        r.reason = R_UNKNOWN_SURFACE;
    }

    public Record get(UpiVpa.Vpa vpa) { return byKey.get(vpa.key()); }

    public Record getByKey(String key) { return byKey.get(key); }

    /**
     * The user bind: every address currently judged {@code OWN}, in a stable
     * order. A real customer carries one to three of these.
     */
    public List<Record> ownAddresses() {
        List<Record> out = new ArrayList<>();
        for (Record r : byKey.values()) if (r.verdict == Verdict.OWN) out.add(r);
        Collections.sort(out, (a, b) -> a.vpa.key().compareTo(b.vpa.key()));
        return out;
    }

    public List<Record> counterpartyAddresses() {
        List<Record> out = new ArrayList<>();
        for (Record r : byKey.values()) if (r.verdict == Verdict.COUNTERPARTY) out.add(r);
        Collections.sort(out, (a, b) -> a.vpa.key().compareTo(b.vpa.key()));
        return out;
    }

    /** Addresses seen in both roles; the operator should look at these. */
    public List<Record> conflicts() {
        List<Record> out = new ArrayList<>();
        for (Record r : byKey.values()) if (r.conflict()) out.add(r);
        return out;
    }

    public int size() { return byKey.size(); }

    /**
     * Plurality vote across observations, for a caller that only has raw text.
     * Convenience for the harness; the service should call {@link #observe}.
     */
    public static String summarise(List<Record> records) {
        if (records == null || records.isEmpty()) return "NONE";
        StringBuilder sb = new StringBuilder();
        for (Record r : records) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(UpiVpa.fingerprint(r.vpa)).append('=').append(r.verdict.name());
        }
        return sb.toString();
    }

    static String lower(String s) { return s == null ? "" : s.toLowerCase(Locale.US); }
}