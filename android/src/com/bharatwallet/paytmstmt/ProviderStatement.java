package com.bharatwallet.paytmstmt;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The proven provider contract for reading a customer's own UPI IDs and their
 * statement, transcribed from the integrations that already work.
 *
 * <p>Source of truth, all read from {@code bw-back/channel-services} and
 * confirmed live on the BW UPI host:
 *
 * <ul>
 *   <li><b>MobiKwik</b> &mdash; {@code /p/upi/vpa/profile/v2} on
 *       {@code appapi.mobikwik.com} returns {@code vpa}, {@code vpaState},
 *       {@code vpaAccountStatus} and {@code enabled}. This is a single
 *       authenticated call that yields the customer's own address.</li>
 *   <li><b>PhonePe</b> &mdash;
 *       {@code /apis/payments/instrument/aggregation/v1/{uid}} is the
 *       authoritative account list; {@code /apis/users/v5.0/profile/user/{uid}
 *       /mapping?includeVpaDetails=true} adds the primary address;
 *       {@code /apis/tstore/v2/units/changes} carries 450 days of
 *       transactions.</li>
 * </ul>
 *
 * <p>This class transcribes the <em>decision rules</em> those integrations
 * apply, not their transport. The app makes no network call: this file exists so
 * the rules are testable offline and so the on-device pipeline can apply the
 * same standard of proof when it reads a screen instead of an API.
 *
 * <p>The rule that matters most: <b>existence is not proof and a shape is not
 * ownership</b>. An address only counts as the customer's own when it is
 * reported active by the provider <em>and</em> tied to the authenticated phone.
 */
public final class ProviderStatement {

    private ProviderStatement() { }

    // --- MobiKwik ----------------------------------------------------------

    public static final String MOBIKWIK_UPI_HOST = "https://appapi.mobikwik.com";
    public static final String MOBIKWIK_HISTORY_HOST = "https://txnhistory.mobikwik.com";
    public static final String MOBIKWIK_WEB_HOST = "https://webapi.mobikwik.com";

    /** The one call that returns the customer's own address. */
    public static final String MOBIKWIK_CURRENT_VPAS = "/p/upi/vpa/profile/v2";
    /** Identity, used to prove the session belongs to the expected phone. */
    public static final String MOBIKWIK_WHOAMI = "/p/account/whoami";

    public static final String VPA_STATE_REGISTERED = "REGISTERED";
    public static final String VPA_ACCOUNT_ACTIVE = "ACTIVE";
    /** States in which the provider reports no usable address at all. */
    public static final String[] VPA_STATE_NONE = {"NOT_SET", "DISABLED"};

    // --- PhonePe -----------------------------------------------------------

    public static final String PHONEPE_INSTRUMENTS = "/apis/payments/instrument/aggregation/v1/";
    public static final String PHONEPE_PROFILE = "/apis/users/v5.0/profile/user/";
    public static final String PHONEPE_VPA_DETAILS = "/apis/payments/v3/upi/";
    public static final String PHONEPE_UNITS_CHANGES = "/apis/tstore/v2/units/changes";

    /** How far back PhonePe's transaction feed is read. */
    public static final int PHONEPE_LOOKBACK_DAYS = 450;
    /** PhonePe's own page size for that feed. */
    public static final int PHONEPE_PAGE_SIZE = 20;
    /** Routing handles PhonePe addresses may use. Never used to infer a bank. */
    public static final String[] PHONEPE_HANDLES = {"ybl", "ibl", "axl"};

    // --- ownership rules ---------------------------------------------------

    public static final String FAIL_NO_SESSION = "UNAUTHORIZED";
    public static final String FAIL_ACCOUNT_MISMATCH = "ACCOUNT_MISMATCH";
    public static final String FAIL_UPI_UNAVAILABLE = "UPI_DISCOVERY_UNAVAILABLE";
    public static final String FAIL_TXN_MISMATCH = "TRANSACTION_ACCOUNT_MISMATCH";

    /**
     * What one row of a statement is allowed to claim.
     *
     * <p>The own endpoint depends on direction and must be read from one side
     * only: a credit puts the customer's address in {@code payee}, a debit puts
     * it in {@code payer}. Scanning both ends and trusting whatever appears
     * would let a counterparty's address pass as the customer's own.
     */
    public enum Direction { CREDIT, DEBIT, UNKNOWN }

    public static Direction directionOf(String paymentType) {
        if (paymentType == null) return Direction.UNKNOWN;
        String t = paymentType.trim().toUpperCase(Locale.US);
        if ("CREDIT".equals(t)) return Direction.CREDIT;
        if ("DEBIT".equals(t)) return Direction.DEBIT;
        return Direction.UNKNOWN;
    }

    /** The field that carries the customer's own address for a direction. */
    public static String ownEndpointField(Direction d) {
        if (d == Direction.CREDIT) return "payeeVpa";
        if (d == Direction.DEBIT) return "payerVpa";
        return null;
    }

    /**
     * Decide whether an address may be presented as the customer's own.
     *
     * @param vpa            the address the provider returned
     * @param vpaState       MobiKwik {@code vpaState}, or null when not supplied
     * @param accountStatus  MobiKwik {@code vpaAccountStatus}, or null
     * @param sessionPhone   the phone the session is bound to
     * @param accountPhone   the phone the provider says the account belongs to
     * @return null when the address may be claimed as own, otherwise a stable
     *         failure code explaining the refusal.
     */
    public static String rejectOwnClaim(String vpa, String vpaState, String accountStatus,
                                        String sessionPhone, String accountPhone) {
        if (vpa == null || vpa.trim().isEmpty()) return FAIL_UPI_UNAVAILABLE;
        if (!UpiVpa.isVpa(vpa.trim().toLowerCase(Locale.US))) return "INVALID_UPI";
        if (vpaState != null && !VPA_STATE_REGISTERED.equalsIgnoreCase(vpaState.trim())) {
            return FAIL_UPI_UNAVAILABLE;
        }
        if (accountStatus != null && !VPA_ACCOUNT_ACTIVE.equalsIgnoreCase(accountStatus.trim())) {
            return FAIL_UPI_UNAVAILABLE;
        }
        if (sessionPhone == null || accountPhone == null) return FAIL_ACCOUNT_MISMATCH;
        if (!samePhone(sessionPhone, accountPhone)) return FAIL_ACCOUNT_MISMATCH;
        return null;
    }

    /** Indian numbers arrive with and without the country code and +91. */
    public static boolean samePhone(String a, String b) {
        String x = normalisePhone(a);
        String y = normalisePhone(b);
        return !x.isEmpty() && x.equals(y);
    }

    public static String normalisePhone(String value) {
        if (value == null) return "";
        String v = value.trim().replaceAll("[^0-9]", "");
        if (v.length() > 10 && v.startsWith("91")) v = v.substring(2);
        return v;
    }

    /**
     * Verify a receipt against the bound addresses.
     *
     * @return null when the receipt is consistent, otherwise a failure code.
     */
    public static String verifyReceipt(Direction d, String payerVpa, String payeeVpa,
                                       List<String> ownAddresses) {
        if (d == null || d == Direction.UNKNOWN) return FAIL_TXN_MISMATCH;
        String own = d == Direction.CREDIT ? payeeVpa : payerVpa;
        if (own == null || own.trim().isEmpty()) return FAIL_TXN_MISMATCH;
        if (ownAddresses == null || ownAddresses.isEmpty()) return FAIL_UPI_UNAVAILABLE;
        String needle = own.trim().toLowerCase(Locale.US);
        for (String candidate : ownAddresses) {
            if (candidate != null && candidate.trim().toLowerCase(Locale.US).equals(needle)) {
                return null;
            }
        }
        return FAIL_TXN_MISMATCH;
    }

    /** Which endpoint a PhonePe VPA check would hit, for review and logs. */
    public static String phonepeVpaDetailsPath(String uid, String vpa) {
        return PHONEPE_VPA_DETAILS + uid + "/operations/vpa/details?vpa=" + vpa;
    }

    /** The PhonePe transaction feed path, with the read window applied. */
    public static String phonepeHistoryPath(String uid) {
        return PHONEPE_UNITS_CHANGES + "?viewVersion=55&duration=" + PHONEPE_LOOKBACK_DAYS
                + "d&viewId=phonepeApp__APPView&size=" + PHONEPE_PAGE_SIZE
                + "&metaId=" + uid + "&sortOrder=DESC";
    }

    /** Addresses a set of counterparty rows does <em>not</em> explain. */
    public static List<String> unexplained(List<String> counterpartyVpas,
                                           List<String> ownAddresses) {
        List<String> out = new ArrayList<>();
        if (counterpartyVpas == null) return out;
        for (String v : counterpartyVpas) {
            if (v == null) continue;
            String key = v.trim().toLowerCase(Locale.US);
            boolean bound = false;
            if (ownAddresses != null) {
                for (String own : ownAddresses) {
                    if (own != null && own.trim().toLowerCase(Locale.US).equals(key)) {
                        bound = true;
                        break;
                    }
                }
            }
            if (!bound) out.add(key);
        }
        return out;
    }
}