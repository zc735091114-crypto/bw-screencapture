package com.bharatwallet.paytmstmt;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which UPI surface a screen is, from its text alone.
 *
 * <p>Shape-based on purpose: the rules key off affordances and verbs, never a
 * marketing banner or a screen title, so a cosmetic copy change cannot flip a
 * payment surface into a capturable one.
 *
 * <p>{@link #PAY} is checked first and wins outright. A payment or checkout
 * surface is never captured, whatever else it contains &mdash; this is the
 * invariant that keeps the pilot incapable of participating in a transaction.
 */
public final class ScreenClass {

    /** Case-insensitive matching, spelled once. */
    private static final int CASE_INSENSITIVE = Pattern.CASE_INSENSITIVE;

    public enum Kind {
        /** Payment / checkout / send-money. Never captured. */
        PAY,
        /** The account's own UPI ID list. Primary user-bind surface. */
        UPI_MANAGE,
        /** Transaction list. Counterparty addresses only. */
        UPI_HISTORY,
        /** Statement export / download surface. */
        STATEMENT,
        /** Anything else, including the home screen and OTP entry. */
        UNKNOWN
    }

    public final Kind kind;
    /** Which shape fired, for the operator log. Never the screen text. */
    public final String matchedBy;

    ScreenClass(Kind kind, String matchedBy) {
        this.kind = kind;
        this.matchedBy = matchedBy;
    }

    /** True when addresses on this surface may be captured at all. */
    public boolean capturable() { return kind == Kind.UPI_MANAGE; }

    // --- payment affordances: presence of any one of these means refuse -------

    private static final List<String[]> PAY_SHAPES = new ArrayList<>();
    static {
        PAY_SHAPES.add(new String[]{"pay_verb",
                "\\b(pay\\s+now|proceed\\s+to\\s+pay|confirm\\s+pay|make\\s+payment|"
                        + "pay\\s+using|send\\s+money|send\\s+amount|transfer\\s+amount|"
                        + "add\\s+money|add\\s+to\\s+wallet|request\\s+money|collect\\s+request)\\b"});
        PAY_SHAPES.add(new String[]{"upi_entry",
                "\\b(enter\\s+upi\\s*id|enter\\s+new\\s+upi\\s*id|pay\\s+using\\s+upi\\s*id|"
                        + "create\\s+upi\\s*id|add\\s+upi\\s*id|activate\\s+upi\\s*id|"
                        + "verify\\s+upi\\s*id|upi\\s*id\\s+limit)\\b"});
        PAY_SHAPES.add(new String[]{"amount_entry",
                "\\b(enter\\s+amount|amount\\s+to\\s+pay|₹\\s*amount|"
                        + "how\\s+much\\s+do\\s+you\\s+want\\s+to\\s+send)\\b"});
        PAY_SHAPES.add(new String[]{"auth_or_pin",
                "\\b(enter\\s+upi\\s+pin|upi\\s+pin|set\\s+upi\\s+pin|"
                        + "mpin|merchant\\s+pin)\\b"});
    }

    // --- own-address surface: the user-bind target --------------------------

    private static final List<String[]> MANAGE_SHAPES = new ArrayList<>();
    static {
        MANAGE_SHAPES.add(new String[]{"your_upi",
                "\\b(your\\s+upi\\s*id|your\\s+vpa|your\\s+upi\\s*ids|"
                        + "my\\s+upi\\s*id|my\\s+vpa|upi\\s*id\\s*details)\\b"});
        MANAGE_SHAPES.add(new String[]{"manage_upi",
                "\\b(manage\\s+upi|upi\\s*settings|upi\\s*profile|"
                        + "upi\\s*ids?\\s*(list|and\\s+payment)|primary\\s+upi\\s*id)\\b"});
        MANAGE_SHAPES.add(new String[]{"add_upi",
                "\\b(add\\s+a\\s+new\\s+upi|add\\s+upi\\s*id|remove\\s+upi\\s*id|"
                        + "set\\s+as\\s+primary)\\b"});
    }

    // --- counterparty list ---------------------------------------------------

    private static final Pattern HISTORY_SHAPE = Pattern.compile(
            "\\b(paid\\s+to|money\\s+sent\\s+to|received\\s+from|money\\s+received\\s+from|"
                    + "transaction\\s+details|payment\\s+to|paid\\s+via|refund\\s+from|"
                    + "upi\\s+ref(?:\\s*no)?)\\b", CASE_INSENSITIVE);

    // --- statement export ------------------------------------------------------

    private static final List<String[]> STATEMENT_SHAPES = new ArrayList<>();
    static {
        STATEMENT_SHAPES.add(new String[]{"statement_export",
                "\\b(download\\s+statement|export\\s+statement|statement\\s+of\\s+account|"
                        + "account\\s+statement|passbook|pass\\s*book|generate\\s+statement|"
                        + "download\\s+passbook)\\b"});
    }

    private ScreenClass withFallback() { return this; }

    public static ScreenClass classify(String text) {
        String blob = text == null ? "" : text;
        if (blob.trim().isEmpty()) return new ScreenClass(Kind.UNKNOWN, "shape:EMPTY");

        // 1. Payment surfaces first, and they win.
        for (String[] shape : PAY_SHAPES) {
            if (Pattern.compile(shape[1], CASE_INSENSITIVE).matcher(blob).find()) {
                return new ScreenClass(Kind.PAY, "shape:" + shape[0]);
            }
        }

        // 2. Own-address management surface.
        for (String[] shape : MANAGE_SHAPES) {
            if (Pattern.compile(shape[1], CASE_INSENSITIVE).matcher(blob).find()) {
                return new ScreenClass(Kind.UPI_MANAGE, "shape:" + shape[0]);
            }
        }

        // 3. Statement export surface.
        for (String[] shape : STATEMENT_SHAPES) {
            if (Pattern.compile(shape[1], CASE_INSENSITIVE).matcher(blob).find()) {
                return new ScreenClass(Kind.STATEMENT, "shape:" + shape[0]);
            }
        }

        // 4. Counterparty transaction list.
        Matcher m = HISTORY_SHAPE.matcher(blob);
        if (m.find()) {
            return new ScreenClass(Kind.UPI_HISTORY, "shape:" + normalize(m.group(1)));
        }

        return new ScreenClass(Kind.UNKNOWN, "shape:UNKNOWN").withFallback();
    }

    private static String normalize(String label) {
        return label.toLowerCase(Locale.US).replaceAll("\\s+", "_");
    }

    @Override public String toString() { return kind + "/" + matchedBy; }
}