package com.bharatwallet.lab.feeder;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteCallbackList;
import android.util.Log;

import com.bharatwallet.lab.ILabCallback;
import com.bharatwallet.lab.ILabService;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Lab feeder host service.
 *
 * <p>Exposes {@link ILabService} over Binder so a BW client can exercise a real
 * bind lifecycle, real AIDL marshalling and real transaction-windowing logic
 * without any provider account.
 *
 * <h3>What this is</h3>
 * An integration test fixture. It answers {@code getUPIList()} and
 * {@code getPayList*} from {@link LabFixtures} and nothing else. Every payload
 * is synthetic, in a reserved domain, and self-identifying.
 *
 * <h3>What this is not</h3>
 * It is not a provider adapter and its output is not acceptance evidence. The
 * service deliberately has no code path that could return a routable UPI
 * handle, a real counterparty, or {@code providerAuthorized=true}. A consumer
 * that treats these rows as a genuine own-UPI read is misusing the fixture.
 *
 * <p>This mirrors the architecture of the TopPay and LongFaFaPay Binder
 * clients (bind by action, verify the host's signing digest, forward calls,
 * push async events through a callback) while keeping our own package, action,
 * descriptor and signing identity. The shape is reused; the impersonation is not.
 *
 * @see LabFixtures for the synthetic-data guarantees
 */
public final class LabService extends Service {

    private static final String TAG = "BwLabFeeder";

    /** Reported in every payload so a consumer cannot mistake the origin. */
    static final String SOURCE = "LAB_FEEDER";

    private final IBinder binder = new LabServiceBinder();
    private final LabFixtures fixtures = new LabFixtures();

    /**
     * Registered client callbacks. A RemoteCallbackList is used rather than a
     * single field so a rebound client cannot orphan an in-flight callback and
     * so a dead client's binder is dropped automatically on death.
     */
    private final RemoteCallbackList<ILabCallback> callbacks = new RemoteCallbackList<>();

    private final AtomicInteger bindCount = new AtomicInteger();

    // ------------------------------------------------------------------
    // Service lifecycle
    // ------------------------------------------------------------------

    @Override
    public IBinder onBind(Intent intent) {
        bindCount.incrementAndGet();
        Log.i(TAG, "bound; action=" + (intent == null ? "null" : intent.getAction()));
        return binder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        callbacks.kill();
        Log.i(TAG, "unbound");
        return true;
    }

    @Override
    public void onDestroy() {
        callbacks.kill();
        super.onDestroy();
    }

    // AIDL generates Stub as `abstract class Stub extends Binder implements
    // ILabService`, so it is extended, never implemented.
    private final class LabServiceBinder extends ILabService.Stub {

        @Override
        public String ping() {
            JSONObject body = new JSONObject();
            try {
                body.put("pong", true);
                body.put("service", "bw-lab-feeder");
                body.put("fixtureBuild", LabFixtures.FIXTURE_BUILD);
                body.put("bindCount", bindCount.get());
                return wrap(body);
            } catch (JSONException exc) {
                return error("INTERNAL", "ping envelope failed", exc);
            }
        }

        @Override
        public String onEvent(String event, String key, String value) {
            // Retained for interface parity with the reference contracts. The
            // feeder records the call and echoes it; it drives nothing.
            Log.i(TAG, "client event: " + event + " " + key);
            broadcast("client_event", event == null ? "" : event, value == null ? "" : value);
            JSONObject body = new JSONObject();
            try {
                body.put("accepted", true);
                body.put("event", event);
                return wrap(body);
            } catch (JSONException exc) {
                return error("INTERNAL", "event envelope failed", exc);
            }
        }

        @Override
        public String setLabCallback(ILabCallback callback) {
            callbacks.kill();
            String registered = "none";
            if (callback != null) {
                callbacks.register(callback);
                registered = "registered";
            }
            JSONObject body = new JSONObject();
            try {
                body.put("callback", registered);
                body.put("registrantCount", callbacks.getRegisteredCallbackCount());
                return wrap(body);
            } catch (JSONException exc) {
                return error("INTERNAL", "callback envelope failed", exc);
            }
        }

        @Override
        public String getPayList(int page, int pageSize) {
            JSONArray rows = fixtures.payListPage(page, pageSize);
            JSONObject body = new JSONObject();
            try {
                body.put("page", page);
                body.put("pageSize", pageSize);
                body.put("count", rows.length());
                body.put("totalCount", fixtures.rowCount());
                body.put("hasMore", page * Math.max(1, pageSize) < fixtures.rowCount());
                body.put("rows", rows);
                return wrap(body);
            } catch (JSONException exc) {
                return error("INTERNAL", "pay list envelope failed", exc);
            }
        }

        @Override
        public String getUPIList() {
            try {
                JSONArray upis = fixtures.upiList();
                JSONObject body = new JSONObject();
                body.put("count", upis.length());
                body.put("items", upis);
                return wrap(body);
            } catch (JSONException exc) {
                return error("INTERNAL", "upi list envelope failed", exc);
            }
        }

        @Override
        public String getRequestMeta() {
            try {
                return wrap(fixtures.requestMeta());
            } catch (JSONException exc) {
                return error("INTERNAL", "request meta failed", exc);
            }
        }

        @Override
        public String getPayListByTimeStamp(long startTimeMillis, long endTimeMillis) {
            JSONArray rows = fixtures.payListBetween(startTimeMillis, endTimeMillis);
            JSONObject body = new JSONObject();
            try {
                body.put("startTime", startTimeMillis);
                body.put("endTime", endTimeMillis);
                body.put("count", rows.length());
                body.put("rows", rows);
                return wrap(body);
            } catch (JSONException exc) {
                return error("INTERNAL", "window envelope failed", exc);
            }
        }

        @Override
        public String getUPIRequestMeta() {
            try {
                return wrap(fixtures.upiRequestMeta());
            } catch (JSONException exc) {
                return error("INTERNAL", "upi meta failed", exc);
            }
        }

        @Override
        public String getLabDiagnostics() {
            JSONObject body = new JSONObject();
            try {
                body.put("bindCount", bindCount.get());
                body.put("registeredCallbacks", callbacks.getRegisteredCallbackCount());
                body.put("rowCount", fixtures.rowCount());
                body.put("epochMillis", fixtures.epochMillis());
                body.put("inventoryDigest", fixtures.inventoryDigest());
                body.put("ownUpiDomain", LabFixtures.OWN_UPI_DOMAIN);
                body.put("counterpartyDomain", LabFixtures.COUNTERPARTY_DOMAIN);
                return wrap(body);
            } catch (JSONException exc) {
                return error("INTERNAL", "diagnostics failed", exc);
            }
        }

        // ------------------------------------------------------
        // Helpers
        // ------------------------------------------------------

        private String wrap(JSONObject body) throws JSONException {
            JSONObject env = fixtures.envelope("data", body);
            // Duplicate the flags at the top level as well as inside the
            // envelope so a shallow consumer still sees the provenance.
            env.put("source", SOURCE);
            env.put("providerAuthorized", false);
            env.put("synthetic", true);
            env.put("acceptanceEligible", false);
            return env.toString();
        }

        private String error(String code, String detail, Exception cause) {
            Log.w(TAG, code + ": " + detail, cause);
            JSONObject env = new JSONObject();
            try {
                env.put("source", SOURCE);
                env.put("providerAuthorized", false);
                env.put("synthetic", true);
                env.put("acceptanceEligible", false);
                env.put("errorCode", code);
                env.put("errorDetail", detail);
            } catch (JSONException ignored) {
                // Cannot fail: keys and values above are all non-null.
            }
            return env.toString();
        }

        private void broadcast(String event, String key, String value) {
            int count = callbacks.beginBroadcast();
            try {
                for (int i = 0; i < count; i++) {
                    try {
                        callbacks.getBroadcastItem(i).onEvent(event, key, value);
                    } catch (Exception exc) {
                        // A dead client must not stop the others.
                        Log.w(TAG, "callback failed", exc);
                    }
                }
            } finally {
                callbacks.finishBroadcast();
            }
        }
    }
}