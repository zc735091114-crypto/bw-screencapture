package com.bharatwallet.lab.feeder;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Deterministic synthetic transaction + UPI fixtures for {@link LabService}.
 *
 * <h3>Why these values are unusable as real data</h3>
 * Every address uses a domain reserved by RFC 2606 ({@code .invalid}), which
 * by definition can never be registered or resolved in the public UPI
 * namespace. That is a deliberate safety property, not a stylistic choice: a
 * fixture that could be mistaken for a live handle is a liability. There is no
 * configuration flag, seed or hidden mode that produces a routable address.
 *
 * <p>The envelopes carry {@code source="LAB_FEEDER"}, {@code providerAuthorized=false}
 * and {@code synthetic=true}. Consumers are expected to reject any response
 * whose {@code providerAuthorized} is not explicitly false; see
 * {@code src/provider_base.py::LabFeederProvider}.
 *
 * <h3>Determinism</h3>
 * Fixture content is a pure function of a fixed seed plus an epoch anchored at
 * construction time, so a test can assert on exact rows and so
 * {@link #getPayListByTimeStamp(long, long)} returns a stable window across
 * runs. The generator holds no mutable state beyond the anchor.
 */
final class LabFixtures {

    /** RFC 2606 reserved TLD. Unregistrable and unresolvable, forever. */
    static final String OWN_UPI_DOMAIN = "bwlab.invalid";
    static final String COUNTERPARTY_DOMAIN = "lab.invalid";

    /** Fixed seed so the fixture inventory never drifts between builds. */
    private static final long SEED = 20261006L;

    static final String SCHEMA_VERSION = "lab-feeder/1";
    static final String FIXTURE_BUILD = "lab-fixture-1.0.0";

    /** Rows generated per run. Small on purpose: this is a fixture, not a corpus. */
    static final int DEFAULT_ROW_COUNT = 24;

    private static final long HOUR_MILLIS = 3_600_000L;

    /** Stable anchor for all relative timestamps. */
    private final long epochMillis;
    private final int rowCount;
    private final String ownUpi;
    private final String secondUpi;

    private final List<JSONObject> rows = new ArrayList<>();

    LabFixtures() {
        this(DEFAULT_ROW_COUNT);
    }

    LabFixtures(int rowCount) {
        this.rowCount = Math.max(1, Math.min(rowCount, 200));
        // Truncate to the hour so repeated service restarts inside one hour
        // produce identical timestamps; a test can then compare exact values.
        this.epochMillis = System.currentTimeMillis() / HOUR_MILLIS * HOUR_MILLIS;
        this.ownUpi = "labowner@" + OWN_UPI_DOMAIN;
        this.secondUpi = "labowner2@" + OWN_UPI_DOMAIN;
        build();
    }

    // ------------------------------------------------------------------
    // Construction
    // ------------------------------------------------------------------

    private void build() {
        for (int i = 0; i < rowCount; i++) {
            rows.add(buildRow(i));
        }
    }

    private JSONObject buildRow(int index) {
        JSONObject row = new JSONObject();
        try {
            // Newest first, one row per hour, walking backwards from the anchor.
            long ts = epochMillis - (long) index * HOUR_MILLIS;

            row.put("txnId", String.format(Locale.US, "LAB-%06d", index + 1));
            row.put("reference", String.format(Locale.US, "LABREF%08d", index + 1));
            row.put("timestamp", ts);
            row.put("timestampIso", iso(ts));
            // Whole-rupee amounts only, in paise, so no float rounding can make
            // a fixture disagree with its own stated amount.
            row.put("amountPaisa", 10000L * (1L + (index % 9)));
            row.put("currency", "INR");
            // Every third row is a credit, so a client exercising directional
            // filtering has both cases without a second fixture set.
            row.put("direction", index % 3 == 0 ? "CREDIT" : "DEBIT");
            row.put("status", index % 7 == 5 ? "PENDING" : "SUCCESS");
            row.put("ownUpi", index % 2 == 0 ? ownUpi : secondUpi);
            row.put("counterpartyVpa",
                    String.format(Locale.US, "fixture%02d@%s", index % 5, COUNTERPARTY_DOMAIN));
            row.put("counterpartyName",
                    String.format(Locale.US, "LAB FIXTURE %02d", index % 5));
            row.put("note", "synthetic lab fixture - not a bank record");
            row.put("synthetic", true);
        } catch (JSONException exc) {
            // JSONObject.put only throws on a null key or a non-finite number.
            // Neither is possible above, so this cannot fire at runtime.
            throw new IllegalStateException("fixture construction failed", exc);
        }
        return row;
    }

    // ------------------------------------------------------------------
    // Envelope
    // ------------------------------------------------------------------

    /**
     * Wrap a payload in the self-identifying envelope every response uses.
     *
     * @param key   payload key, e.g. {@code "rows"} or {@code "upis"}
     * @param value payload value
     */
    JSONObject envelope(String key, Object value) throws JSONException {
        JSONObject env = new JSONObject();
        env.put("source", LabService.SOURCE);
        env.put("providerAuthorized", false);
        env.put("synthetic", true);
        env.put("schema", SCHEMA_VERSION);
        env.put("generatedAt", iso(System.currentTimeMillis()));
        env.put("acceptanceEligible", false);
        env.put(key, value);
        return env;
    }

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    /** Page through rows, newest first. page is 1-based. */
    JSONArray payListPage(int page, int pageSize) {
        int size = Math.max(1, Math.min(pageSize, 200));
        int from = Math.max(0, (page - 1) * size);
        JSONArray out = new JSONArray();
        for (int i = from; i < Math.min(from + size, rows.size()); i++) {
            out.put(rows.get(i));
        }
        return out;
    }

    /** Inclusive epoch-millisecond window. Used for incremental-read tests. */
    JSONArray payListBetween(long startMillis, long endMillis) {
        JSONArray out = new JSONArray();
        for (JSONObject row : rows) {
            long ts = row.optLong("timestamp", Long.MIN_VALUE);
            if (ts >= startMillis && ts <= endMillis) {
                out.put(row);
            }
        }
        return out;
    }

    /** The account holder's own UPI identifiers. */
    JSONArray upiList() throws JSONException {
        JSONArray out = new JSONArray();
        out.put(upiEntry(ownUpi, "primary", 18));
        out.put(upiEntry(secondUpi, "secondary", 3));
        return out;
    }

    private JSONObject upiEntry(String vpa, String label, int historyCount) throws JSONException {
        JSONObject entry = new JSONObject();
        entry.put("vpa", vpa);
        entry.put("label", label);
        entry.put("bankName", "LAB FIXTURE BANK");
        entry.put("holderName", "LAB FIXTURE HOLDER");
        entry.put("frequent", historyCount >= 10);
        entry.put("observedRowCount", historyCount);
        entry.put("synthetic", true);
        return entry;
    }

    // ------------------------------------------------------------------
    // Metadata
    // ------------------------------------------------------------------

    JSONObject requestMeta() throws JSONException {
        JSONObject meta = new JSONObject();
        meta.put("fixtureBuild", FIXTURE_BUILD);
        meta.put("schema", SCHEMA_VERSION);
        meta.put("rowCount", rows.size());
        meta.put("ownUpiCount", 2);
        meta.put("domain", OWN_UPI_DOMAIN);
        meta.put("epochMillis", epochMillis);
        meta.put("currency", "INR");
        return meta;
    }

    JSONObject upiRequestMeta() throws JSONException {
        JSONObject meta = new JSONObject();
        meta.put("fixtureBuild", FIXTURE_BUILD);
        meta.put("ownUpiCount", 2);
        meta.put("domain", OWN_UPI_DOMAIN);
        meta.put("frequentThreshold", 10);
        meta.put("ownership", "fixture-declared");
        return meta;
    }

    /** Stable fingerprint of the fixture inventory, for diagnostics. */
    String inventoryDigest() {
        StringBuilder sb = new StringBuilder();
        for (JSONObject row : rows) {
            sb.append(row.optString("txnId")).append(':')
              .append(row.optLong("timestamp", 0L)).append(';');
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(sb.toString().getBytes("UTF-8"));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 12 && i < digest.length; i++) {
                hex.append(String.format(Locale.US, "%02x", digest[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException | java.io.UnsupportedEncodingException exc) {
            // SHA-256 and UTF-8 are mandated on every Android release.
            return "unavailable";
        }
    }

    long epochMillis() {
        return epochMillis;
    }

    int rowCount() {
        return rows.size();
    }

    static String iso(long millis) {
        SimpleDateFormat fmt =
                new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
        return fmt.format(new Date(millis));
    }
}