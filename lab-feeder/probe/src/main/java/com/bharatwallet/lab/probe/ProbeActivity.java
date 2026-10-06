package com.bharatwallet.lab.probe;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.widget.ScrollView;
import android.widget.TextView;

import com.bharatwallet.lab.feeder.client.LabBinderClient;

import org.json.JSONArray;

/**
 * Minimal driver that exercises {@link LabBinderClient} end to end against the
 * lab feeder and prints what came back.
 *
 * <p>Exists so the bind path, the signature check and the provenance check can
 * be verified on a real device with one command, and so the result is visible
 * rather than only logged. It reads nothing from any other app: it binds to our
 * own feeder package and nothing else.
 */
public final class ProbeActivity extends Activity {

    private static final String TAG = "BwLabProbe";

    private TextView output;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        output = new TextView(this);
        output.setPadding(32, 32, 32, 32);
        output.setTextSize(12f);
        scroll.addView(output);
        setContentView(scroll);

        // Off the UI thread: bind + IPC must not block the main looper.
        new Thread(() -> runProbe(), "lab-probe").start();
    }

    private void runProbe() {
        StringBuilder log = new StringBuilder();
        say(log, "bw-lab-feeder probe");

        LabBinderClient client = new LabBinderClient(this);

        say(log, "installed:      " + client.isInstalled());
        say(log, "signature match: " + client.hasExpectedSignature());

        if (!client.bind(5000)) {
            say(log, "BIND FAILED: " + client.lastErrorCode()
                    + " / " + client.lastErrorDetail());
            publish(log.toString());
            return;
        }

        say(log, "bound + verified");
        say(log, "");

        // --- UPI list -------------------------------------------------
        JSONArray upis = client.readOwnUpis();
        say(log, "getUPIList -> " + upis.length() + " own UPI handle(s):");
        for (int i = 0; i < upis.length(); i++) {
            say(log, "  " + upis.optString(i));
        }
        say(log, "");

        // --- Full page ------------------------------------------------
        String page = client.getPayList(1, 5);
        say(log, "getPayList(page=1,size=5):");
        say(log, indent(page));
        say(log, "");

        // --- Time window ---------------------------------------------
        // A one-hour window ending now, to prove windowing works.
        long end = System.currentTimeMillis();
        long start = end - 3_600_000L;
        JSONArray rows = client.readHistory(start, end);
        say(log, "getPayListByTimeStamp(last 1h) -> " + rows.length() + " row(s)");
        for (int i = 0; i < Math.min(rows.length(), 3); i++) {
            say(log, "  " + rows.optJSONObject(i));
        }
        say(log, "");

        // --- Bad window, to prove the argument guard ------------------
        client.getPayListByTimeStamp(end, start - 1000);
        say(log, "inverted window -> " + client.lastErrorCode()
                + " (expected INVALID_ARGS)");
        say(log, "");

        // --- Diagnostics ----------------------------------------------
        say(log, "getLabDiagnostics:");
        say(log, indent(client.getDiagnostics()));

        client.unbind();
        say(log, "");
        say(log, "PROBE OK");
        publish(log.toString());
    }

    private static String indent(String json) {
        if (json == null) {
            return "  (null)";
        }
        String[] parts = json.split(",");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            sb.append("  ").append(part.trim()).append('\n');
        }
        return sb.toString();
    }

    private static void say(StringBuilder sb, String line) {
        Log.i(TAG, line);
        sb.append(line).append('\n');
    }

    private void publish(String text) {
        runOnUiThread(() -> output.setText(text));
    }
}