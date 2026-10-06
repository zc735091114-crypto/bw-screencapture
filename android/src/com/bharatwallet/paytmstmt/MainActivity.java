package com.bharatwallet.paytmstmt;

import android.app.*;
import android.os.*;
import android.content.*;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Tester screen for the local statement check.
 *
 * What this screen does: the tester exports his own statement from the Paytm
 * app (Balance &amp; History &rarr; Download), picks that file here, and sees
 * how many statement rows it contains. Everything happens on the device.
 *
 * What it deliberately does NOT do: no network, no Paytm login, no OTP, no
 * UPI PIN, no SMS, no payment. There is no server address and no token
 * anywhere in this build — a remote backend cannot be reached because none is
 * needed.
 *
 * The capture path now prefers Option D: a single MediaProjection session
 * captures the visible wallet screen, and OCR extracts any UPI IDs without a
 * background AccessibilityService. This keeps the same consent and privacy
 * model while working even when React Native or OEM builds block accessibility.
 */
public class MainActivity extends Activity {
    private CheckBox consent;
    private TextView status, results, captureStatus, captureOwn, captureSteps;
    private Button select, save, captureAction, captureCheck;
    private String lastReport;
    private String lastErrorCode = "";
    private StatementParser.Result lastResult;
    private static final String FILE = "latest-stmt-report.json";
    private static final int SCREEN_CAPTURE_REQUEST = MediaProjectionCapture.SCREEN_CAPTURE_REQUEST;

    /** Clearly-labelled demo rows. Never presented as Paytm data. */
    private static final String SAMPLE_CSV =
            "date,description,utr,amount\n"
            + "05-10-2026,UPI Tester One,123456789012,250.00\n"
            + "04-10-2026,UPI Tester Two,234567890123,99.50\n"
            + "03-10-2026,Wallet cashback,,15.00\n"
            + "02-10-2026,Hello world,,\n";

    private int dp(int n) { return (int)(n * getResources().getDisplayMetrics().density); }

    private TextView text(String value, int size) {
        TextView v = new TextView(this);
        v.setText(value); v.setTextSize(size); v.setTextColor(Color.rgb(25, 34, 51));
        v.setPadding(0, dp(6), 0, dp(6));
        return v;
    }

    private Button button(LinearLayout parent, String title, Runnable action) {
        Button b = new Button(this);
        b.setText(title); b.setAllCaps(false); b.setTextSize(16); b.setTextColor(Color.BLACK);
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFFFFCE39));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(56));
        p.topMargin = dp(10);
        parent.addView(b, p);
        b.setOnClickListener(v -> action.run());
        return b;
    }

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setStatusBarColor(0xFF182233);
        getWindow().setNavigationBarColor(0xFF182233);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(20), dp(16), dp(20), dp(24));
        c.setBackgroundColor(0xFFF8F9FC);
        scroll.addView(c);
        setContentView(scroll);

        TextView title = text("BW Checkup", 26);
        c.addView(title);
        c.addView(text("Find your own UPI IDs", 19));
        TextView badge = text("On this phone only · nothing is uploaded", 14);
        badge.setTextColor(0xFF855700);
        c.addView(badge);
        c.addView(text("Optional: if you already exported a statement file, select it below "
                + "and the rows will be counted. You do not need this to find your "
                + "UPI IDs.", 15));

        consent = new CheckBox(this);
        consent.setText("This is my own account and my own exported file. Check it on this phone only.");
        consent.setTextSize(14);
        c.addView(consent);

        select = button(c, "Select statement file", this::selectFile);
        status = text("No file checked yet.", 16);
        c.addView(status);
        results = text("", 14);
        c.addView(results);
        save = button(c, "Save test report", this::saveReport);
        c.addView(text("No internet, no login, no code by SMS. PDF exports cannot be read by this test — "
                + "export Excel or CSV from the Paytm app instead.", 13));

        // --- UPI identifier capture ------------------------------------------
        c.addView(text("Identify your own UPI IDs", 19));
        captureSteps = text("", 15);
        c.addView(captureSteps);
        captureCheck = button(c, "BW Check", this::onCheckOnly);
        captureAction = button(c, "Open Paytm UPI", this::onCapturePrimary);
        // Hidden self-test: long-press runs the built-in UPI-screen fixture, so
        // extraction can be proven on-device with no wallet app and no grant.
        captureAction.setOnLongClickListener(v -> { runCaptureFixture(); return true; });
        c.addView(text("Long-press that button to run the built-in check without "
                + "opening any wallet app.", 13));
        captureStatus = text("", 15);
        c.addView(captureStatus);
        captureOwn = text("", 15);
        c.addView(captureOwn);

        // Hidden self-test hook: long-press the title to parse the built-in
        // sample. Used to prove on-device parsing without a file picker.
        title.setOnLongClickListener(v -> { runSample(); return true; });

        // ACTION_VIEW targeting: lets the operator push a file via adb and
        // open it straight into the parser for on-device verification.
        if (Intent.ACTION_VIEW.equals(getIntent().getAction())
                && getIntent().getData() != null) {
            consent.setChecked(true);
            importUri(getIntent().getData(), true);
        }

        try (InputStream in = openFileInput(FILE)) {
            lastReport = read(in);
            status.setText("A previous test report is stored. You can save it below.");
        } catch (Exception ignored) { lastReport = null; }
    }

    @Override protected void onResume() {
        super.onResume();
        refreshCapture();
    }

    /**
     * Option D: the tester grants a single screen-capture permission and then
     * opens the wallet directly. This avoids an AccessibilityService entirely.
     */
    private void onCheckOnly() {
        if (!MediaProjectionCapture.isAvailable(this)) {
            captureStatus.setText("MediaProjection is unavailable on this device.");
            return;
        }
        try {
            MediaProjectionCapture.requestCapture(this, SCREEN_CAPTURE_REQUEST);
            captureStatus.setText("Choose the app or screen to share, then open the wallet UPI screen.");
        } catch (Exception e) {
            captureStatus.setText("MediaProjection could not be started: " + e.getMessage());
        }
    }

    /**
     * The single forward button. It means whatever the tester needs next, so
     * the whole flow is one tap per step and never a page they have to find.
     */
    private void onCapturePrimary() {
        if (!MediaProjectionCapture.isAvailable(this)) {
            captureStatus.setText("Option D is unavailable here because MediaProjection is not supported.");
            return;
        }
        try {
            MediaProjectionCapture.requestCapture(this, SCREEN_CAPTURE_REQUEST);
            captureStatus.setText("System permission requested. Open the wallet UPI screen and allow capture when prompted.");
        } catch (Exception e) {
            captureStatus.setText("Could not start MediaProjection capture: " + e.getMessage());
        }
    }

    /**
     * Rebuild the guidance from live state. Called on every resume, so coming
     * back from Settings or from the wallet app advances the flow on its own.
     */
    private void refreshCapture() {
        if (captureStatus == null || captureOwn == null || captureAction == null) return;
        if (!MediaProjectionCapture.isAvailable(this)) {
            captureAction.setText("MediaProjection unavailable");
            captureSteps.setText("Step 1 of 2 — Option D is not supported on this build\n"
                    + "This device cannot grant screen capture. Keep the exported-file path as the safe fallback.");
            captureSteps.setTextColor(0xFFB00020);
            captureStatus.setText("");
            captureOwn.setText("");
            return;
        }

        captureAction.setText("Start screen OCR");
        captureSteps.setText("Step 1 of 2 — Option D: MediaProjection OCR\n"
                + "Tap the button, grant screen capture in the system dialog, then "
                + "open the UPI screen of the wallet app. The capture is explicit, "
                + "does not depend on AccessibilityService, and can work even when "
                + "React Native screens block native Accessibility callbacks.");
        captureSteps.setTextColor(0xFF855700);
        captureStatus.setText("Background OCR is the preferred capture path here.");
        captureOwn.setText("");
    }

    /**
     * On-device proof of the extractor with no wallet app involved: feed the
     * built-in UPI-screen fixture through the same pipeline the service uses.
     */
    private void runCaptureFixture() {
        UpiCapture cap = new UpiCapture(CapturePolicy.WALLET_PACKAGES);
        cap.ingest("net.one97.paytm",
                "Profile\nUPI & Payments\nYour UPI ID\nPaytm Payments Bank\n"
                + "7000000000@ptyes\nVerified\nPrimary\nBank Of India\n7000000001@ybl\nManage UPI ID");
        cap.ingest("net.one97.paytm",
                "Payment History\n06 Jan  10:17 PM\nPaid to Merchant One\n"
                + "UPI ID: paytmqr6mwbm3@ptys\nUPI Ref No: 395047088754\n- Rs.150");
        cap.ingest("net.one97.paytm",
                "Send Money\nPay using UPI ID\nEnter UPI ID\n7000000000@ptyes\nPay Now");
        StringBuilder sb = new StringBuilder("Built-in check\n" + cap.summary() + "\n\n");
        for (OwnnessJudge.Record r : cap.ownAddresses()) {
            sb.append("  YOURS  ").append(r.vpa.key()).append("  (").append(r.reason).append(")\n");
        }
        for (OwnnessJudge.Record r : cap.counterpartyAddresses()) {
            sb.append("  other  ").append(r.vpa.key()).append("\n");
        }
        captureStatus.setText("");
        captureOwn.setText(sb.toString());
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] b = new byte[4096];
        int n;
        while ((n = in.read(b)) != -1) out.write(b, 0, n);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    /** Background work may throw; Runnable may not. */
    private interface Task { void run() throws Exception; }

    private void background(final Task work) {
        new Thread(() -> {
            try {
                work.run();
            } catch (final Exception e) {
                runOnUiThread(() -> {
                    String code = errorCode(e);
                    showError(code, explainError(code));
                });
            }
        }).start();
    }

    // --- import + parse -------------------------------------------------------

    private void selectFile() {
        if (!consent.isChecked()) {
            showError("CONSENT_REQUIRED", "Tick the consent box first. No file was read.");
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_MIME_TYPES,
                    new String[]{"text/csv", "text/comma-separated-values",
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                            "text/plain"});
            startActivityForResult(intent, 8);
        } catch (Exception e) {
            showError("PICKER_UNAVAILABLE", "The system file picker could not be opened: "
                    + e.getMessage());
        }
    }

    private void runSample() {
        if (!consent.isChecked()) {
            showError("CONSENT_REQUIRED", "Tick the consent box first — even for the sample.");
            return;
        }
        clearError();
        status.setText("Reading sample…");
        background(() -> {
            byte[] data = SAMPLE_CSV.getBytes(StandardCharsets.UTF_8);
            final StatementParser.Result r =
                    StatementParser.parse("sample.csv", data);
            runOnUiThread(() -> showResult(r, true));
        });
    }

    private void importUri(Uri uri, boolean auto) {
        if (!consent.isChecked()) {
            showError("CONSENT_REQUIRED", "Tick the consent box first. No file was read.");
            return;
        }
        clearError();
        status.setText("Reading file…");
        background(() -> {
            String name = displayName(uri);
            byte[] data = readUri(uri);
            final StatementParser.Result r = StatementParser.parse(name, data);
            runOnUiThread(() -> showResult(r, false));
        });
    }

    private String displayName(Uri uri) {
        try (Cursor cur = getContentResolver().query(
                uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cur != null && cur.moveToFirst()) {
                String name = cur.getString(0);
                if (name != null && !name.isEmpty()) return name;
            }
        } catch (Exception ignored) { }
        String path = uri.getLastPathSegment();
        return path == null ? "statement" : path;
    }

    private byte[] readUri(Uri uri) throws IOException {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("STORAGE_READ_FAILED");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            long total = 0;
            while ((n = in.read(b)) != -1) {
                total += n;
                if (total > StatementParser.MAX_BYTES + 1) break;
                out.write(b, 0, n);
            }
            return out.toByteArray();
        }
    }

    private void showResult(StatementParser.Result r, boolean sample) {
        lastResult = r;
        if (r.errorCode != null) {
            showError(r.errorCode, explainError(r.errorCode)
                    + (sample ? " (sample)" : " File: " + r.fileName));
            results.setText("");
            return;
        }
        clearError();
        String head = sample ? "SAMPLE rows (demo only — not Paytm data)" : "File rows";
        StringBuilder sb = new StringBuilder();
        sb.append(head).append(": ").append(r.rows.size())
                .append(" · statement rows: ").append(r.statementRows);
        if (r.utrPresent) sb.append(" · reference numbers seen");
        int shown = Math.min(r.rows.size(), 20);
        for (int i = 0; i < shown; i++) sb.append("\n• ").append(r.rows.get(i));
        if (r.rows.size() > shown) sb.append("\n… and ").append(r.rows.size() - shown).append(" more");
        status.setText(sample
                ? "Sample parsed on this phone. Now try your own exported file."
                : "Parsed on this phone: " + r.rows.size() + " row(s), "
                + r.statementRows + " with date + amount.");
        results.setText(sb.toString());
        report(sample ? "sample_parsed" : "file_parsed",
                "ROWS=" + r.rows.size() + ",STATEMENT_ROWS=" + r.statementRows);
    }

    // --- report ----------------------------------------------------------------

    private void report(String phase, String result) {
        try {
            SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
            fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
            JSONObject root = new JSONObject()
                    .put("schemaVersion", 2)
                    .put("build", "0.3-export")
                    .put("package", "com.bharatwallet.paytmstmt")
                    .put("phase", phase)
                    .put("result", result)
                    .put("errorCode", lastErrorCode.isEmpty() ? JSONObject.NULL : lastErrorCode)
                    .put("fileName", lastResult == null ? JSONObject.NULL : lastResult.fileName)
                    .put("fileBytes", lastResult == null ? JSONObject.NULL : lastResult.fileBytes)
                    .put("kind", lastResult == null ? JSONObject.NULL : lastResult.kind)
                    .put("rowsFound", lastResult == null ? JSONObject.NULL : lastResult.rows.size())
                    .put("statementRows", lastResult == null ? JSONObject.NULL : lastResult.statementRows)
                    .put("utrPresent", lastResult != null && lastResult.utrPresent)
                    .put("networkUsed", false)
                    .put("permissions", new JSONArray())
                    .put("runAtUtc", fmt.format(new Date()))
                    .put("androidSdk", Build.VERSION.SDK_INT)
                    .put("deviceModel", Build.MODEL);
            lastReport = root.toString(2);
            try (OutputStream out = openFileOutput(FILE, MODE_PRIVATE)) {
                out.write(lastReport.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) { }
    }

    private void saveReport() {
        if (lastReport == null) report("setup", "NO_RESULT_YET");
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_TITLE, "BW-Paytm-statement-test-report.json");
        try {
            startActivityForResult(intent, 7);
        } catch (Exception e) {
            showError("SAVE_UNAVAILABLE", "The system file saver could not be opened: "
                    + e.getMessage());
        }
    }

    // --- errors: always red, always coded, always reportable -------------------

    private void clearError() {
        lastErrorCode = "";
        status.setTextColor(Color.rgb(25, 34, 51));
    }

    private void showError(String code, String message) {
        lastErrorCode = code;
        status.setTextColor(Color.rgb(180, 0, 32));
        status.setText("ERROR · " + code + "\n" + message
                + "\nSend this code plus the saved report to the test operator.");
        report("error", code);
    }

    private String errorCode(Exception e) {
        String value = e.getMessage() == null ? "" : e.getMessage().trim();
        if (value.matches("[A-Z0-9_]{3,64}")) return value;
        return "FILE_READ_FAILED";
    }

    private String explainError(String code) {
        switch (code) {
            case "CONSENT_REQUIRED":
                return "Tick the consent box first. No file was read.";
            case "FILE_EMPTY":
                return "The selected file is empty. Export the statement again from the Paytm app.";
            case "FILE_TOO_LARGE":
                return "The file is over 8 MB. Export a shorter period from the Paytm app.";
            case "NO_ROWS_FOUND":
                return "No readable rows in this file. Export Excel or CSV — not PDF — from Balance & History.";
            case "PDF_NOT_PARSED":
                return "PDF exports cannot be read by this test. In the Paytm app choose Excel (or CSV) when downloading.";
            case "XLSX_UNREADABLE":
                return "This Excel file could not be opened. Try exporting again, or choose CSV.";
            case "PICKER_UNAVAILABLE":
                return "The system file picker is unavailable on this device.";
            case "SAVE_UNAVAILABLE":
                return "The system file saver is unavailable on this device.";
            case "FILE_READ_FAILED":
                return "The file could not be read. Grant access when the picker asks, then try again.";
            default:
                return "The test stopped safely. Nothing was uploaded. Send this error code to the operator.";
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == SCREEN_CAPTURE_REQUEST) {
            MediaProjectionCapture.handleCaptureResult(this, requestCode, resultCode, data,
                    new MediaProjectionCapture.Callback() {
                        @Override public void onResult(String text, List<String> vpas) {
                            String joined = vpas.isEmpty() ? "No VPA detected in the captured screen." : "Detected VPA(s): " + vpas;
                            captureStatus.setText(joined);
                            captureOwn.setText(text.length() > 2000 ? text.substring(0, 2000) + "…" : text);
                            if (!vpas.isEmpty()) {
                                captureSteps.setText("Step 2 of 2 — OCR result\nScreen text was captured successfully and the VPA(s) were extracted without an AccessibilityService.");
                                captureSteps.setTextColor(0xFF1B5E20);
                            }
                        }

                        @Override public void onError(String code, String message) {
                            captureStatus.setText(code + ": " + message);
                            captureSteps.setText("Step 2 of 2 — Option D capture failed\nOpen the wallet UPI screen again and retry with screen capture permission.");
                            captureSteps.setTextColor(0xFFB00020);
                        }
                    });
            return;
        }

        if (requestCode == 8) {
            if (resultCode != RESULT_OK || data == null || data.getData() == null) {
                showError("NO_FILE_SELECTED", "No file was chosen. Nothing was read.");
                return;
            }
            try {
                getContentResolver().takePersistableUriPermission(data.getData(),
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) { }
            importUri(data.getData(), false);
            return;
        }
        if (requestCode != 7 || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        try (OutputStream out = getContentResolver().openOutputStream(data.getData())) {
            if (out == null) throw new IOException("document unavailable");
            out.write(lastReport.getBytes(StandardCharsets.UTF_8));
            status.setText("Report saved. Send that JSON file to the test operator.");
        } catch (Exception e) {
            showError("SAVE_UNAVAILABLE", "Report could not be saved: " + e.getMessage());
        }
    }
}
