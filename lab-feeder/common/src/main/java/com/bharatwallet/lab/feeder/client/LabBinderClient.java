package com.bharatwallet.lab.feeder.client;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import com.bharatwallet.lab.ILabCallback;
import com.bharatwallet.lab.ILabService;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Client half of the lab feeder bind.
 *
 * <p>Reproduces the architecture the TopPay and LongFaFaPay Cordova plugins use
 * — bind by package + action + service class, verify the host's signing digest,
 * forward calls, receive async events through a registered callback — with two
 * differences that matter:
 *
 * <ol>
 *   <li>It targets <em>our</em> feeder package, never a provider app.</li>
 *   <li>Every reply is checked for provenance before use. A payload whose
 *       {@code providerAuthorized} is not explicitly {@code false} is rejected,
 *       because a fixture must never be promoted into an acceptance path.</li>
 * </ol>
 *
 * <p>Error codes deliberately mirror the reference taxonomy
 * ({@code SERVICE_DISCONNECTED}, {@code REMOTE_ERROR}, {@code UNSUPPORTED_ACTION},
 * {@code INVALID_ARGS}) so client-side handling can be written once.
 */
public final class LabBinderClient {

    private static final String TAG = "BwLabClient";

    public static final String FEEDER_PACKAGE = "com.bharatwallet.lab.feeder";
    public static final String FEEDER_ACTION = "com.bharatwallet.lab.BIND_SERVICE";
    public static final String FEEDER_SERVICE_CLASS = "com.bharatwallet.lab.feeder.LabService";
    public static final String FEEDER_PERMISSION = "com.bharatwallet.lab.BIND_FEEDER";

    /** Result codes, matching the reference contracts' vocabulary. */
    public static final String SERVICE_DISCONNECTED = "SERVICE_DISCONNECTED";
    public static final String REMOTE_ERROR = "REMOTE_ERROR";
    public static final String UNSUPPORTED_ACTION = "UNSUPPORTED_ACTION";
    public static final String INVALID_ARGS = "INVALID_ARGS";
    public static final String NOT_INSTALLED = "NOT_INSTALLED";
    public static final String SIGNATURE_MISMATCH = "SIGNATURE_MISMATCH";
    public static final String BIND_TIMEOUT = "BIND_TIMEOUT";
    public static final String NOT_BOUND = "NOT_BOUND";
    public static final String PROVENANCE_REJECTED = "PROVENANCE_REJECTED";

    private final Context context;
    private final CountDownLatch bound = new CountDownLatch(1);

    private volatile ILabService service;
    private volatile boolean verified;
    private String lastErrorCode;
    private String lastErrorDetail;

    private final List<LabEventListener> listeners = new ArrayList<>();

    /** Async event sink, mirroring the reference {@code onEvent} callback. */
    public interface LabEventListener {
        void onLabEvent(String event, String key, String value);
    }

    public LabBinderClient(Context context) {
        this.context = context.getApplicationContext();
    }

    // ------------------------------------------------------------------
    // Bind lifecycle
    // ------------------------------------------------------------------

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ILabService.Stub.asInterface(binder);
            bound.countDown();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
            // Mirrors the reference behaviour: a lost binder is reported, not
            // silently retried, so the caller can re-establish deliberately.
            fail(SERVICE_DISCONNECTED, "feeder disconnected");
        }
    };

    /**
     * Verify the feeder is installed and signed by our key, then bind.
     *
     * @param timeoutMillis how long to wait for {@code onServiceConnected}
     * @return true when bound and verified
     */
    public boolean bind(int timeoutMillis) {
        if (!isInstalled()) {
            fail(NOT_INSTALLED, FEEDER_PACKAGE + " is not installed");
            return false;
        }
        if (!hasExpectedSignature()) {
            fail(SIGNATURE_MISMATCH,
                    "feeder signing digest is not the expected one");
            return false;
        }

        Intent intent = new Intent(FEEDER_ACTION);
        intent.setComponent(new ComponentName(FEEDER_PACKAGE, FEEDER_SERVICE_CLASS));

        boolean started;
        try {
            started = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
        } catch (SecurityException exc) {
            // The signature-level permission was refused.
            fail(REMOTE_ERROR, "bind permission denied: " + exc.getMessage());
            return false;
        }
        if (!started) {
            fail(SERVICE_DISCONNECTED, "bindService returned false");
            return false;
        }

        try {
            if (!bound.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
                context.unbindService(connection);
                fail(BIND_TIMEOUT, "no onServiceConnected within " + timeoutMillis + "ms");
                return false;
            }
        } catch (InterruptedException exc) {
            Thread.currentThread().interrupt();
            fail(SERVICE_DISCONNECTED, "bind interrupted");
            return false;
        }

        ILabService local = service;
        if (local == null) {
            fail(SERVICE_DISCONNECTED, "binder was null after connect");
            return false;
        }

        // Verify the peer reports itself synthetic before any data is read.
        verified = verifyProvenance(local);
        if (!verified) {
            unbind();
            return false;
        }
        clearError();
        return true;
    }

    public void unbind() {
        try {
            context.unbindService(connection);
        } catch (IllegalArgumentException exc) {
            // Already unbound; nothing to release.
            Log.d(TAG, "unbind skipped: " + exc.getMessage());
        }
        service = null;
        verified = false;
    }

    public boolean isBound() {
        return service != null;
    }

    public boolean isVerified() {
        return verified;
    }

    // ------------------------------------------------------------------
    // Package / signature checks
    // ------------------------------------------------------------------

    public boolean isInstalled() {
        try {
            context.getPackageManager().getPackageInfo(FEEDER_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException exc) {
            return false;
        }
    }

    /**
     * Compare the feeder's signing-certificate digests against our own.
     *
     * <p>Same technique the reference clients use: read the installed package's
     * certificate history and require our key to be present. This is not a
     * courtesy — it is what makes "the reply came from our own feeder"
     * checkable rather than assumed.
     *
     * @return true when our certificate is among the feeder's signers
     */
    public boolean hasExpectedSignature() {
        List<String> theirs = readSignatureDigests(FEEDER_PACKAGE);
        if (theirs.isEmpty()) {
            return false;
        }
        for (String ours : readSignatureDigests(context.getPackageName())) {
            if (theirs.contains(ours)) {
                return true;
            }
        }
        return false;
    }

    private List<String> readSignatureDigests(String packageName) {
        List<String> out = new ArrayList<>();
        if (packageName == null || packageName.isEmpty()) {
            return out;
        }
        PackageManager pm = context.getPackageManager();
        try {
            Signature[] certs;
            if (Build.VERSION.SDK_INT >= 28) {
                PackageInfo info = pm.getPackageInfo(packageName,
                        PackageManager.GET_SIGNING_CERTIFICATES);
                if (info.signingInfo == null) {
                    return out;
                }
                certs = info.signingInfo.hasMultipleSigners()
                        ? info.signingInfo.getApkContentsSigners()
                        : info.signingInfo.getSigningCertificateHistory();
            } else {
                certs = pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures;
            }
            if (certs == null) {
                return out;
            }
            for (Signature cert : certs) {
                String digest = sha256(cert.toByteArray());
                if (digest != null) {
                    out.add(digest);
                }
            }
        } catch (Exception exc) {
            Log.w(TAG, "signature read failed for " + packageName, exc);
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Calls
    // ------------------------------------------------------------------

    /** Ping the feeder. Returns the envelope, or an error envelope. */
    public String ping() {
        return call("ping", new LabCall() {
            @Override
            public String invoke(ILabService svc) throws RemoteException {
                return svc.ping();
            }
        });
    }

    /** Register for async events. Mirrors {@code setCallback}. */
    public String setCallback() {
        return call("setLabCallback", new LabCall() {
            @Override
            public String invoke(ILabService svc) throws RemoteException {
                return svc.setLabCallback(new ILabCallback.Stub() {
                    @Override
                    public void onEvent(String event, String key, String value) {
                        dispatch(event, key, value);
                    }
                });
            }
        });
    }

    /** The account holder's own UPI identifiers, as fixtures. */
    public String getUpiList() {
        return call("getUPIList", new LabCall() {
            @Override
            public String invoke(ILabService svc) throws RemoteException {
                return svc.getUPIList();
            }
        });
    }

    public String getPayList(int page, int pageSize) {
        if (page < 1) {
            fail(INVALID_ARGS, "page must be >= 1");
            return null;
        }
        if (pageSize < 1) {
            fail(INVALID_ARGS, "pageSize must be >= 1");
            return null;
        }
        return call("getPayList", new LabCall() {
            @Override
            public String invoke(ILabService svc) throws RemoteException {
                return svc.getPayList(page, pageSize);
            }
        });
    }

    /** Time-windowed read for incremental-history logic. */
    public String getPayListByTimeStamp(long startMillis, long endMillis) {
        if (startMillis > endMillis) {
            fail(INVALID_ARGS, "startTime must not exceed endTime");
            return null;
        }
        return call("getPayListByTimeStamp", new LabCall() {
            @Override
            public String invoke(ILabService svc) throws RemoteException {
                return svc.getPayListByTimeStamp(startMillis, endMillis);
            }
        });
    }

    public String getRequestMeta() {
        return call("getRequestMeta", new LabCall() {
            @Override
            public String invoke(ILabService svc) throws RemoteException {
                return svc.getRequestMeta();
            }
        });
    }

    public String getUpiRequestMeta() {
        return call("getUPIRequestMeta", new LabCall() {
            @Override
            public String invoke(ILabService svc) throws RemoteException {
                return svc.getUPIRequestMeta();
            }
        });
    }

    public String getDiagnostics() {
        return call("getLabDiagnostics", new LabCall() {
            @Override
            public String invoke(ILabService svc) throws RemoteException {
                return svc.getLabDiagnostics();
            }
        });
    }

    // ------------------------------------------------------------------
    // Convenience readers (provenance-checked)
    // ------------------------------------------------------------------

    /**
     * Parse {@code getUpiList} into a JSON array of VPA strings.
     *
     * @return the array, or an empty array when the call or check failed
     */
    public JSONArray readOwnUpis() {
        JSONObject data = requireData(getUpiList());
        if (data == null) {
            return new JSONArray();
        }
        JSONArray items = data.optJSONArray("items");
        if (items == null) {
            return new JSONArray();
        }
        JSONArray out = new JSONArray();
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item != null && item.optString("vpa").length() > 0) {
                out.put(item.optString("vpa"));
            }
        }
        return out;
    }

    /**
     * Parse a transaction window into a JSON array of rows.
     *
     * @return the rows, or an empty array when the call or check failed
     */
    public JSONArray readHistory(long startMillis, long endMillis) {
        JSONObject data = requireData(getPayListByTimeStamp(startMillis, endMillis));
        if (data == null) {
            return new JSONArray();
        }
        JSONArray rows = data.optJSONArray("rows");
        return rows == null ? new JSONArray() : rows;
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private interface LabCall {
        String invoke(ILabService svc) throws RemoteException;
    }

    private String call(String action, LabCall invocation) {
        ILabService local = service;
        if (local == null) {
            fail(SERVICE_DISCONNECTED, "no bound service for " + action);
            return null;
        }
        String raw;
        try {
            raw = invocation.invoke(local);
        } catch (RemoteException exc) {
            // Matches the reference clients: a remote failure invalidates the
            // connection rather than being retried on a possibly dead binder.
            service = null;
            fail(REMOTE_ERROR, action + " failed: " + exc.getMessage());
            return null;
        }
        if (raw == null) {
            fail(REMOTE_ERROR, action + " returned null");
            return null;
        }
        if (!isAcceptableProvenance(raw)) {
            fail(PROVENANCE_REJECTED,
                    action + " reply did not declare itself synthetic");
            return null;
        }
        clearError();
        return raw;
    }

    /**
     * A reply is acceptable only if it declares itself synthetic and
     * unauthorized. Anything else — a missing flag, a truthy
     * {@code providerAuthorized}, an acceptance-eligible claim — is refused.
     */
    private static boolean isAcceptableProvenance(String raw) {
        try {
            JSONObject env = new JSONObject(raw);
            return "LAB_FEEDER".equals(env.optString("source"))
                    && !env.optBoolean("providerAuthorized", true)
                    && env.optBoolean("synthetic", false)
                    && !env.optBoolean("acceptanceEligible", true);
        } catch (JSONException exc) {
            return false;
        }
    }

    private boolean verifyProvenance(ILabService svc) {
        String raw = ping();
        if (raw == null) {
            return false;
        }
        try {
            JSONObject env = new JSONObject(raw);
            boolean ok = "bw-lab-feeder".equals(env.optJSONObject("data") == null
                            ? "" : env.optJSONObject("data").optString("service"));
            if (!ok) {
                fail(PROVENANCE_REJECTED, "peer identity mismatch");
            }
            return ok;
        } catch (JSONException exc) {
            fail(PROVENANCE_REJECTED, "ping envelope unparseable");
            return false;
        }
    }

    /** Unwrap the {@code data} object, re-checking provenance at the boundary. */
    private JSONObject requireData(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            JSONObject env = new JSONObject(raw);
            if (!isAcceptableProvenance(raw)) {
                fail(PROVENANCE_REJECTED, "payload failed provenance check");
                return null;
            }
            return env.optJSONObject("data");
        } catch (JSONException exc) {
            fail(REMOTE_ERROR, "payload unparseable");
            return null;
        }
    }

    public void addListener(LabEventListener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(LabEventListener listener) {
        listeners.remove(listener);
    }

    private void dispatch(String event, String key, String value) {
        for (LabEventListener listener : listeners) {
            try {
                listener.onLabEvent(event, key, value);
            } catch (RuntimeException exc) {
                Log.w(TAG, "listener threw", exc);
            }
        }
    }

    public String lastErrorCode() {
        return lastErrorCode;
    }

    public String lastErrorDetail() {
        return lastErrorDetail;
    }

    private void fail(String code, String detail) {
        lastErrorCode = code;
        lastErrorDetail = detail;
        Log.w(TAG, code + ": " + detail);
    }

    private void clearError() {
        lastErrorCode = null;
        lastErrorDetail = null;
    }

    private static String sha256(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(bytes);
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format(Locale.US, "%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exc) {
            return null;
        }
    }
}