package com.bharatwallet.paytmstmt;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * UPI Virtual Payment Address (VPA) grammar, per the NPCI shape:
 * {@code <handle>@<psp-or-bank>}.
 *
 * <p>Real handles observed on a live statement, all of which must match:
 * {@code 7008979585@ptyes} (phone-number handle, Paytm Payments Bank),
 * {@code 9289604880@ybl}, {@code paytmqr6mwbm3@ptys},
 * {@code poweraccess.jarmyjar@axisbank}.
 *
 * <p>Grammar, deliberately narrow so that ordinary screen text does not
 * manufacture handles:
 * <ul>
 *   <li>handle: 1&ndash;64 chars from {@code [A-Za-z0-9._-]}, first and last
 *       alphanumeric, and either contains a digit or is at least two
 *       characters long;</li>
 *   <li>{@code @} with exactly one;</li>
 *   <li>suffix: 2&ndash;32 chars from {@code [A-Za-z0-9-]}, first character a
 *       letter. No dot, so {@code gmail.com} can never be read as a
 *       suffix;</li>
 *   <li>the character after the suffix must not continue the token.</li>
 * </ul>
 *
 * <p>Pure {@code java.util} string ops only, so the same bytecode runs on the
 * JVM contract tests and on the device.
 */
public final class UpiVpa {

    /** Longest handle the NPCI permits, and our scan bound. */
    public static final int MAX_HANDLE = 64;
    /** Suffix bound. Kept short; real suffixes are 2&ndash;14. */
    public static final int MAX_SUFFIX = 32;
    public static final int MIN_SUFFIX = 2;

    /**
     * Handles that are internal identifiers rather than payable addresses.
     *
     * <p>Wallets return two different kinds of {@code x@y} string: real
     * addresses, and per-installation or per-user ids that merely have the same
     * shape. The rule comes from the PhonePe and MobiKwik providers in
     * {@code bw-back/channel-services}, which reject these before treating an
     * address as payable: a {@code u}-prefixed long digit run, or a long
     * lowercase hex run.
     *
     * <p>Without this, {@code u12345678901@x} and a 20-character hex handle
     * would both be bound as a customer's own UPI ID.
     */
    static final java.util.regex.Pattern INTERNAL_HANDLE =
            java.util.regex.Pattern.compile("(?:u[0-9]{10,}|[0-9a-f]{20,})");

    private UpiVpa() { }

    private static boolean isHandleChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-';
    }

    private static boolean isSuffixChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9') || c == '-';
    }

    private static boolean isAlpha(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private static boolean isDigit(char c) { return c >= '0' && c <= '9'; }

    private static boolean isAlnum(char c) { return isAlpha(c) || isDigit(c); }

    /**
     * One parsed address.
     *
     * <p>{@code toString} and {@code logLabel} deliberately redact the handle:
     * an address identifies a person, and the pilot has no need to write it
     * anywhere except the encrypted store. Logs get the suffix and a handle
     * fingerprint only.
     */
    public static final class Vpa {
        public final String handle;
        public final String suffix;
        public final int start;
        public final int end;

        Vpa(String handle, String suffix, int start, int end) {
            this.handle = handle;
            this.suffix = suffix;
            this.start = start;
            this.end = end;
        }

        /** Canonical lowercase form, used for de-duplication. */
        public String key() { return (handle + "@" + suffix).toLowerCase(Locale.US); }

        /** True when the handle is a bare phone number, the dominant real shape. */
        public boolean isPhoneNumberHandle() {
            for (int i = 0; i < handle.length(); i++) {
                if (!isDigit(handle.charAt(i))) return false;
            }
            return handle.length() >= 6 && handle.length() <= 14;
        }

        @Override public String toString() { return key(); }

        @Override public boolean equals(Object o) {
            return o instanceof Vpa && ((Vpa) o).key().equals(key());
        }

        @Override public int hashCode() { return key().hashCode(); }
    }

    /**
     * Redacted label safe for logs: keeps the suffix (a public constant such as
     * {@code ptyes}) and the handle length, never the handle itself.
     */
    public static String logLabel(Vpa v) {
        return "***@" + v.suffix + "(h" + v.handle.length() + ")";
    }

    /** Validate a single candidate {@code handle@suffix}. Used by tests and stores. */
    public static boolean isVpa(String candidate) {
        if (candidate == null) return false;
        int at = candidate.indexOf('@');
        if (at <= 0 || at != candidate.lastIndexOf('@')) return false;
        return validHandle(candidate.substring(0, at))
                && validSuffix(candidate.substring(at + 1));
    }

    private static boolean validHandle(String h) {
        if (h.isEmpty() || h.length() > MAX_HANDLE) return false;
        if (!isAlnum(h.charAt(0)) || !isAlnum(h.charAt(h.length() - 1))) return false;
        boolean digit = false;
        for (int i = 0; i < h.length(); i++) {
            char c = h.charAt(i);
            if (!isHandleChar(c)) return false;
            if (isDigit(c)) digit = true;
        }
        if (!digit && h.length() < 2) return false;
        // Shape-alike internal ids are not payable addresses.
        return !INTERNAL_HANDLE.matcher(h).matches();
    }

    /** True when the handle looks like a wallet-internal id rather than a VPA. */
    public static boolean isInternalHandle(String handle) {
        return handle != null && INTERNAL_HANDLE.matcher(handle).matches();
    }

    private static boolean validSuffix(String s) {
        if (s.length() < MIN_SUFFIX || s.length() > MAX_SUFFIX) return false;
        if (!isAlpha(s.charAt(0))) return false;
        for (int i = 0; i < s.length(); i++) {
            if (!isSuffixChar(s.charAt(i))) return false;
        }
        return true;
    }

    /**
     * Every VPA in {@code text}, in order of first appearance, de-duplicated
     * case-insensitively.
     *
     * <p>Scans for {@code @} and walks outward, so an address embedded in a
     * longer sentence is found without needing word boundaries.
     */
    public static List<Vpa> extract(String text) {
        List<Vpa> found = new ArrayList<>();
        if (text == null || text.isEmpty()) return found;
        Map<String, Boolean> seen = new LinkedHashMap<>();
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) != '@') continue;
            Vpa v = at(text, i);
            if (v == null) continue;
            if (seen.put(v.key(), Boolean.TRUE) == null) found.add(v);
        }
        return found;
    }

    /** Convenience: the canonical keys only, de-duplicated, order preserved. */
    public static List<String> extractKeys(String text) {
        List<String> keys = new ArrayList<>();
        for (Vpa v : extract(text)) keys.add(v.key());
        return keys;
    }

    /**
     * Parse the address whose {@code @} sits at {@code at}. Returns null when
     * the surrounding characters disqualify it.
     */
    static Vpa at(String text, int at) {
        int left = at - 1;
        while (left >= 0 && isHandleChar(text.charAt(left))
                && (at - left) <= MAX_HANDLE) {
            left--;
        }
        int start = left + 1;
        String handle = text.substring(start, at);
        if (!validHandle(handle)) return null;

        // A character before the handle that could continue an email local
        // part means we are inside an address, not a VPA.
        if (start > 0) {
            char before = text.charAt(start - 1);
            if (isAlnum(before) || before == '_' || before == '@'
                    || before == '%' || before == '+' || before == '-') {
                // '-' and '.' are handle chars, so they cannot appear here; a
                // bare alnum means the handle we cut is only part of a word.
                if (before != '.') return null;
            }
        }

        int end = at + 1;
        while (end < text.length() && isSuffixChar(text.charAt(end))
                && (end - at) <= MAX_SUFFIX) {
            end++;
        }
        String suffix = text.substring(at + 1, end);
        if (!validSuffix(suffix)) return null;

        // The suffix must end the token. This is what rejects emails: in
        // "someone@gmail.com" the walk stops at "gmail" and the following '.'
        // proves we are inside an address.
        if (end < text.length()) {
            char after = text.charAt(end);
            if (after == '.' || isAlnum(after) || after == '_' || after == '-') {
                return null;
            }
        }
        return new Vpa(handle, suffix, start, end);
    }

    /**
     * Coarse handle fingerprint for de-duplication across stores without
     * writing the handle: length plus a checksum character.
     */
    public static String fingerprint(Vpa v) {
        int sum = 0;
        for (int i = 0; i < v.handle.length(); i++) {
            sum = (sum * 31 + v.handle.charAt(i)) & 0x7fffffff;
        }
        return v.suffix + ":" + v.handle.length() + ":" + (sum % 4096);
    }
}