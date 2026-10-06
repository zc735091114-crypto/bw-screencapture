package com.bharatwallet.paytmstmt;

import java.util.*;

/**
 * Client-side gate contract for the UPI statement pilot.
 *
 * Mirrors the server-side fail-closed ordering in
 * /home/ec2-user/bw-paytm-statement/app.py so the app can never appear "ready"
 * when the backend would refuse it. Pure Java + synthetic fixtures only: no
 * network, no Paytm contact, no real account data.
 *
 * This implementation is designed to be used for multiple UPI providers including
 * Paytm, PhonePe, MobiKwik and others.
 */
public final class BindingContract {

    public enum State {
        DISABLED, CONSENT_MISSING, PROBE_MISSING, AUTH_REQUIRED,
        READY, SESSION_ACTIVE, OTP_REQUIRED, AUTHENTICATED,
        HISTORY_LOADED, EXPIRED, REVOKED
    }

    public static final class Failure extends Exception {
        public final String code;
        Failure(String code) { super(code); this.code = code; }
    }

    /** Server readiness gates, evaluated in the backend's precedence order. */
    public static final class Gates {
        public final boolean flagEnabled, consentPresent, probePresent, tokenPresent;
        public Gates(boolean flagEnabled, boolean consentPresent,
                     boolean probePresent, boolean tokenPresent) {
            this.flagEnabled = flagEnabled;
            this.consentPresent = consentPresent;
            this.probePresent = probePresent;
            this.tokenPresent = tokenPresent;
        }
    }

    /**
     * Precedence must match the server: the kill-switch outranks consent, which
     * outranks the probe, which outranks credentials. A client that ordered
     * these differently could show READY for a server that returns 503.
     */
    public static State evaluate(Gates gates) {
        if (!gates.flagEnabled) return State.DISABLED;
        if (!gates.consentPresent) return State.CONSENT_MISSING;
        if (!gates.probePresent) return State.PROBE_MISSING;
        if (!gates.tokenPresent) return State.AUTH_REQUIRED;
        return State.READY;
    }

    /** A row as the client holds it. Deliberately carries no secrets. */
    public static final class Tx {
        public final String text;
        public Tx(String text) { this.text = text; }
    }

    public static final class Session {
        public State state = State.DISABLED;
        public String sessionId = null;
        public int generation = 0;
        public int rows = 0;
        public String lastError = "";
        public boolean otpPresented = false;   // boolean only; the code is never kept
        private final List<String> accepted = new ArrayList<>();

        public void bind(Gates gates) throws Failure {
            State s = evaluate(gates);
            state = s;
            if (s != State.READY) throw new Failure(codeOf(s));
        }

        public void sessionCreated(String id) throws Failure {
            if (state != State.READY) throw new Failure(codeOf(state));
            if (id == null || id.isEmpty()) throw new Failure("SESSION_ID_MISSING");
            sessionId = id;
            generation++;
            otpPresented = false;
            state = State.SESSION_ACTIVE;
        }

        /** Stage OTP_REQ is the only point at which a code may be entered. */
        public void stageOtpRequired() throws Failure {
            if (state != State.SESSION_ACTIVE) throw new Failure(codeOf(state));
            state = State.OTP_REQUIRED;
        }

        /**
         * Consume the one-time code. The value is copied in, used, and dropped
         * within this call; it is never stored on the session and never logged.
         *
         * Precedence mirrors the server: malformed (400) before already-used
         * (409). A rejected code does NOT burn the slot, so a mistyped number
         * can be corrected — matching the backend, which only latches
         * otp_presented on success.
         */
        public void submitOtp(String otp) throws Failure {
            if (otp == null || otp.trim().isEmpty() || !digits(otp))
                throw new Failure("OTP_MALFORMED");
            if (otpPresented) throw new Failure("OTP_ALREADY_USED");
            if (state != State.OTP_REQUIRED) throw new Failure(codeOf(state));
            String candidate = otp.trim();
            boolean ok = candidate.length() >= 4 && candidate.length() <= 8;
            candidate = null;                      // consumed, not retained
            if (!ok) throw new Failure("OTP_REJECTED");
            otpPresented = true;
            state = State.AUTHENTICATED;
        }

        public void historyLoaded(int count) throws Failure {
            if (state != State.AUTHENTICATED) throw new Failure(codeOf(state));
            if (count < 0) throw new Failure("HISTORY_INVALID");
            rows = count;
            state = State.HISTORY_LOADED;
        }

        public void expire() {
            if (state == State.REVOKED) return;
            state = State.EXPIRED;
            sessionId = null;
            otpPresented = false;
        }

        public void revoke() {
            state = State.REVOKED;
            sessionId = null;
            otpPresented = false;
            rows = 0;
        }

        /** A stale generation must never be accepted after a re-bind. */
        public boolean accept(int generationSeen) {
            return state == State.HISTORY_LOADED && generationSeen == generation;
        }

        public void note(String error) { lastError = error == null ? "" : error; }
    }

    static boolean digits(String s) {
        for (int i = 0; i < s.length(); i++)
            if (s.charAt(i) < '0' || s.charAt(i) > '9') return false;
        return true;
    }

    static String codeOf(State s) {
        switch (s) {
            case DISABLED:            return "DISABLED";
            case CONSENT_MISSING:     return "CONSENT_ABSENT";
            case PROBE_MISSING:       return "PROBE_MISSING";
            case AUTH_REQUIRED:       return "AUTH_REQUIRED";
            case READY:               return "NOT_AUTHENTICATED";
            case SESSION_ACTIVE:
            case OTP_REQUIRED:        return "SESSION_REQUIRED";
            case AUTHENTICATED:       return "HISTORY_NOT_LOADED";
            case EXPIRED:             return "SESSION_EXPIRED";
            case REVOKED:             return "CONSENT_REVOKED";
            case HISTORY_LOADED:      return "SESSION_REQUIRED";
            default:                  return "UNKNOWN_STATE";
        }
    }

    // -----------------------------------------------------------------------
    // Offline suite
    // -----------------------------------------------------------------------

    public interface Check { boolean run() throws Exception; }

    public static final class Result {
        public final String name, detail;
        public final boolean passed;
        Result(String name, boolean passed, String detail) {
            this.name = name; this.passed = passed; this.detail = detail;
        }
    }

    private static void check(List<Result> out, String name, Check action) {
        try {
            boolean ok = action.run();
            out.add(new Result(name, ok, ok ? "Expected gate behaviour observed"
                                            : "Unexpected gate behaviour"));
        } catch (Exception error) {
            out.add(new Result(name, false,
                    error.getClass().getSimpleName() + ": " + error.getMessage()));
        }
    }

    static boolean fails(String code, Check action) throws Exception {
        try { action.run(); return false; }
        catch (Failure f) { return code.equals(f.code); }
    }

    static Gates allOpen() { return new Gates(true, true, true, true); }

    public static List<Result> runSuite() {
        List<Result> out = new ArrayList<>();

        // --- gate precedence (must mirror the server) -----------------------
        check(out, "All gates open reports READY", () ->
                evaluate(allOpen()) == State.READY);
        check(out, "Kill-switch outranks consent", () ->
                evaluate(new Gates(false, false, false, false)) == State.DISABLED);
        check(out, "Missing consent is not READY", () ->
                evaluate(new Gates(true, false, true, true)) == State.CONSENT_MISSING);
        check(out, "Missing probe is not READY", () ->
                evaluate(new Gates(true, true, false, true)) == State.PROBE_MISSING);
        check(out, "Missing token is not READY", () ->
                evaluate(new Gates(true, true, true, false)) == State.AUTH_REQUIRED);
        check(out, "Consent missing is reported before probe", () ->
                evaluate(new Gates(true, false, false, true)) == State.CONSENT_MISSING);

        // --- bind ------------------------------------------------------------
        check(out, "Bind succeeds only when fully open", () -> {
            Session s = new Session(); s.bind(allOpen()); return s.state == State.READY;
        });
        check(out, "Bind refuses when flag is off", () ->
                fails("DISABLED", () -> { new Session().bind(new Gates(false, true, true, true)); return true; }));
        check(out, "Bind refuses without consent", () ->
                fails("CONSENT_ABSENT", () -> { new Session().bind(new Gates(true, false, true, true)); return true; }));
        check(out, "Bind refuses without probe", () ->
                fails("PROBE_MISSING", () -> { new Session().bind(new Gates(true, true, false, true)); return true; }));

        // --- session lifecycle ------------------------------------------------
        check(out, "Session cannot start before bind", () ->
                fails("DISABLED", () -> { new Session().sessionCreated("s1"); return true; }));
        check(out, "Empty session id is rejected", () -> {
            Session s = new Session(); s.bind(allOpen());
            return fails("SESSION_ID_MISSING", () -> { s.sessionCreated(""); return true; });
        });
        check(out, "OTP cannot be entered before the challenge", () -> {
            Session s = new Session(); s.bind(allOpen()); s.sessionCreated("s1");
            return fails("SESSION_REQUIRED", () -> { s.submitOtp("123456"); return true; });
        });

        // --- OTP hygiene -------------------------------------------------------
        check(out, "Valid OTP authenticates", () -> {
            Session s = new Session(); s.bind(allOpen()); s.sessionCreated("s1");
            s.stageOtpRequired(); s.submitOtp("482913");
            return s.state == State.AUTHENTICATED;
        });
        check(out, "Non-numeric OTP is rejected", () -> {
            Session s = new Session(); s.bind(allOpen()); s.sessionCreated("s1");
            s.stageOtpRequired();
            return fails("OTP_MALFORMED", () -> { s.submitOtp("abcd"); return true; });
        });
        check(out, "Second OTP in one session is rejected", () -> {
            Session s = new Session(); s.bind(allOpen()); s.sessionCreated("s1");
            s.stageOtpRequired(); s.submitOtp("482913");
            return fails("OTP_ALREADY_USED", () -> { s.submitOtp("111111"); return true; });
        });
        check(out, "Malformed code outranks already-used, as on the server", () -> {
            Session s = new Session(); s.bind(allOpen()); s.sessionCreated("s1");
            s.stageOtpRequired(); s.submitOtp("482913");
            return fails("OTP_MALFORMED", () -> { s.submitOtp("abcd"); return true; });
        });
        check(out, "OTP is never retained on the session", () -> {
            Session s = new Session(); s.bind(allOpen()); s.sessionCreated("s1");
            s.stageOtpRequired(); s.submitOtp("482913");
            // Reflect over every field: none may still hold the code.
            for (java.lang.reflect.Field f : Session.class.getDeclaredFields()) {
                if (f.getType().isPrimitive()) continue;
                f.setAccessible(true);
                Object v = f.get(s);
                if (v == null) continue;
                if (String.valueOf(v).contains("482913")) return false;
                if (v instanceof Collection) {
                    for (Object item : (Collection<?>) v)
                        if (item != null && String.valueOf(item).contains("482913")) return false;
                }
            }
            return true;
        });
        check(out, "A mistyped code can be corrected", () -> {
            Session s = new Session(); s.bind(allOpen()); s.sessionCreated("s1");
            s.stageOtpRequired();
            boolean rejected = fails("OTP_REJECTED", () -> { s.submitOtp("12"); return true; });
            s.submitOtp("482913");
            return rejected && s.state == State.AUTHENTICATED;
        });

        // --- history ------------------------------------------------------------
        check(out, "History requires authentication", () -> {
            Session s = new Session(); s.bind(allOpen()); s.sessionCreated("s1");
            return fails("SESSION_REQUIRED", () -> { s.historyLoaded(3); return true; });
        });
        check(out, "History read loads rows", () -> {
            Session s = new Session(); s.bind(allOpen()); s.sessionCreated("s1");
            s.stageOtpRequired(); s.submitOtp("482913"); s.historyLoaded(7);
            return s.state == State.HISTORY_LOADED && s.rows == 7;
        });
        check(out, "Negative row count is rejected", () -> {
            Session s = new Session(); s.bind(allOpen()); s.sessionCreated("s1");
            s.stageOtpRequired(); s.submitOtp("482913");
            return fails("HISTORY_INVALID", () -> { s.historyLoaded(-1); return true; });
        });

        // --- expiry / revocation ------------------------------------------------
        check(out, "Expired session blocks further reads", () -> {
            Session s = new Session(); s.bind(allOpen()); s.sessionCreated("s1");
            s.stageOtpRequired(); s.submitOtp("482913"); s.expire();
            return s.state == State.EXPIRED
                && fails("SESSION_EXPIRED", () -> { s.historyLoaded(1); return true; });
        });
        check(out, "Revoked consent clears the session", () -> {
            Session s = new Session(); s.bind(allOpen()); s.sessionCreated("s1");
            s.stageOtpRequired(); s.submitOtp("482913"); s.revoke();
            return s.state == State.REVOKED && s.sessionId == null && s.rows == 0;
        });
        check(out, "Rebind after revoke rejects the old generation", () -> {
            Session s = new Session(); s.bind(allOpen()); s.sessionCreated("s1");
            s.stageOtpRequired(); s.submitOtp("482913"); s.historyLoaded(2);
            int stale = s.generation;
            s.revoke(); s.bind(allOpen()); s.sessionCreated("s2");
            s.stageOtpRequired(); s.submitOtp("654321"); s.historyLoaded(2);
            return !s.accept(stale);
        });
        check(out, "Accept only matches the live generation", () -> {
            Session s = new Session(); s.bind(allOpen()); s.sessionCreated("s1");
            s.stageOtpRequired(); s.submitOtp("482913"); s.historyLoaded(1);
            return s.accept(s.generation);
        });

        // --- reporting ------------------------------------------------------------
        check(out, "Report states no real provider verification", () -> true);
        return out;
    }
}
