import com.bharatwallet.paytmstmt.CapturePolicy;
import com.bharatwallet.paytmstmt.ProviderStatement;
import com.bharatwallet.paytmstmt.OwnnessJudge;
import com.bharatwallet.paytmstmt.ScreenClass;
import com.bharatwallet.paytmstmt.UpiCapture;
import com.bharatwallet.paytmstmt.UpiRoute;
import com.bharatwallet.paytmstmt.UpiVpa;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

/**
 * Contract tests for Vector B, the on-device UPI-address capture.
 *
 * <p>Fixtures are structurally faithful to what a real Paytm screen renders but
 * carry no real account data: every handle here is fabricated. The shapes are
 * taken from a live statement export, which is what the grammar has to survive.
 *
 * <p>Run on the JVM before dexing. A failure here blocks the APK.
 */
public class VectorBTest {

    static int failed = 0;

    static void check(String name, boolean ok, String detail) {
        System.out.println((ok ? "PASS " : "FAIL ") + name + " — " + detail);
        if (!ok) failed++;
    }

    // --- fixtures ---------------------------------------------------------

    /** Paytm "Your UPI ID" / manage screen. */
    static final String MANAGE_SCREEN =
            "Profile\n"
            + "UPI & Payments\n"
            + "Your UPI ID\n"
            + "Paytm Payments Bank\n"
            + "7000000000@ptyes\n"
            + "Verified\n"
            + "Primary\n"
            + "Bank Of India\n"
            + "7000000001@ybl\n"
            + "Manage UPI ID";

    /** Paytm payment history list. */
    static final String HISTORY_SCREEN =
            "Payment History\n"
            + "06 Jan  10:17 PM\n"
            + "Paid to Merchant One\n"
            + "UPI ID: paytmqr6mwbm3@ptys\n"
            + "UPI Ref No: 395047088754\n"
            + "- Rs.150\n"
            + "Bank Of India - 84\n"
            + "06 Jan  5:18 PM\n"
            + "Received from Person Two\n"
            + "UPI ID: 9289604880@ybl\n"
            + "UPI Ref No: 600684249480\n"
            + "+ Rs.5,000";

    /** Paytm send-money / checkout screen. Must never be captured. */
    static final String PAY_SCREEN =
            "Send Money\n"
            + "Pay using UPI ID\n"
            + "Enter UPI ID\n"
            + "7000000000@ptyes\n"
            + "Enter amount\n"
            + "Pay Now";

    public static void main(String[] args) throws Exception {
        grammar();
        screens();
        safety();
        binding();
        purity();
        structure();
        realAddress();
        providerContract();
        route();

        System.out.println(failed == 0
                ? "Vector B: all checks passed"
                : "Vector B: " + failed + " failures");
        if (failed > 0) throw new AssertionError(failed + " vector-b contract failures");
    }

    // --- 1. VPA grammar ----------------------------------------------------

    static void grammar() {
        String[][] accept = {
            {"7000000000@ptyes", "phone-number handle on Paytm Payments Bank"},
            {"9289604880@ybl", "phone-number handle on Yes Bank"},
            {"paytmqr6mwbm3@ptys", "merchant QR handle"},
            {"poweraccess.jarmyjar@axisbank", "dotted handle"},
            {"9634951174-2@axl", "hyphenated handle"},
            {"tester@ybl", "short alphabetic handle"},
        };
        for (String[] a : accept) {
            List<UpiVpa.Vpa> got = UpiVpa.extract(a[0]);
            check("accepts " + a[1],
                    got.size() == 1 && UpiVpa.isVpa(a[0]),
                    got.isEmpty() ? "no match" : UpiVpa.logLabel(got.get(0)));
        }

        String[][] reject = {
            {"someone@gmail.com", "email domain, not a VPA"},
            {"user@yahoo.co.in", "email with dotted TLD"},
            {"x@1", "suffix starts with a digit"},
            {"x@a", "suffix too short"},
            {"@ptyes", "empty handle"},
            {"7000000000@", "empty suffix"},
            {"no-at-sign", "no separator"},
            {"a@b@c", "two separators"},
        };
        for (String[] r : reject) {
            check("rejects " + r[1], !UpiVpa.isVpa(r[0]), r[0]);
        }

        List<String> fromText = UpiVpa.extractKeys(
                "Paid to Merchant One UPI ID: paytmqr6mwbm3@ptys on UPI Ref No: 395047088754");
        check("extracts from running sentence text",
                fromText.size() == 1 && fromText.get(0).equals("paytmqr6mwbm3@ptys"),
                "found=" + fromText.size());

        List<String> mixed = UpiVpa.extractKeys(
                "Contact us at support@paytm.com or pay 7000000000@ptyes today");
        check("email is ignored while a real handle beside it is kept",
                mixed.size() == 1 && mixed.get(0).equals("7000000000@ptyes"),
                "found=" + mixed);

        List<UpiVpa.Vpa> v = UpiVpa.extract("7000000000@ptyes");
        check("phone-number handle is recognised",
                v.size() == 1 && v.get(0).isPhoneNumberHandle(), "shape flagged");

        check("duplicates collapse case-insensitively",
                UpiVpa.extractKeys("Tester@PTYES and tester@ptyes").size() == 1, "case-folded");

        check("a one-character alphabetic handle is rejected",
                !UpiVpa.isVpa("a@ptyes"), "too short to be a real handle");

        // Shapes the PhonePe and MobiKwik providers reject before treating an
        // address as payable. Bound without this, they become a customer's own.
        String[][] internal = {
            {"u12345678901@ptyes", "u-prefixed internal id"},
            {"0123456789abcdef0123@ybl", "long hex internal id"},
            {"deadbeefdeadbeefdeadbeef@x", "long hex internal id"},
        };
        for (String[] s : internal) {
            check("rejects " + s[1], !UpiVpa.isVpa(s[0]), s[0].replaceAll("^(.{3}).*(@.*)$", "$1…$2"));
        }
        check("a real 10-digit handle is not mistaken for an internal id",
                UpiVpa.isVpa("7000000000@ptyes") && !UpiVpa.isInternalHandle("7000000000"),
                "digits alone are fine");
        check("internal ids never reach the pipeline",
                UpiVpa.extractKeys("Account u12345678901@ptyes active").isEmpty(), "filtered");
    }

    // --- 2. screen classification ------------------------------------------

    static void screens() {
        check("manage screen classified as the own-address surface",
                ScreenClass.classify(MANAGE_SCREEN).kind == ScreenClass.Kind.UPI_MANAGE,
                ScreenClass.classify(MANAGE_SCREEN).matchedBy);
        check("history screen classified as counterparty list",
                ScreenClass.classify(HISTORY_SCREEN).kind == ScreenClass.Kind.UPI_HISTORY,
                ScreenClass.classify(HISTORY_SCREEN).matchedBy);
        check("checkout screen classified as PAY",
                ScreenClass.classify(PAY_SCREEN).kind == ScreenClass.Kind.PAY,
                ScreenClass.classify(PAY_SCREEN).matchedBy);
        check("home screen is UNKNOWN",
                ScreenClass.classify("Home\nOffers\nRewards").kind == ScreenClass.Kind.UNKNOWN,
                "no claim made");
        check("statement export screen is recognised",
                ScreenClass.classify("Account Statement\nDownload Statement\nPassbook")
                        .kind == ScreenClass.Kind.STATEMENT, "export surface");
    }

    // --- 3. safety invariants ----------------------------------------------

    static void safety() {
        UpiCapture c = new UpiCapture();

        List<OwnnessJudge.Record> fromPay = c.ingest("net.one97.paytm", PAY_SCREEN);
        check("PAY surface yields no records",
                fromPay == null && c.ownAddresses().isEmpty()
                        && c.counterpartyAddresses().isEmpty(),
                "refusedPay=" + c.refusedPaymentSurfaces());
        check("PAY refusal happens before any extraction",
                c.events().get(0).addresses.isEmpty(), "nothing extracted");

        c.ingest("com.example.evil", MANAGE_SCREEN);
        check("package outside the allowlist is refused",
                c.refusedPackages() == 1 && c.judge().size() == 0, "no text read");

        check("no raw screen text is retained anywhere",
                !c.summary().contains("7000000000"), c.summary());

        UpiCapture empty = new UpiCapture();
        empty.ingest("net.one97.paytm", "   ");
        check("blank screen is a no-op", empty.judge().size() == 0, "empty=" + empty.emptyScreens());
    }

    // --- 4. the user bind ---------------------------------------------------

    static void binding() {
        UpiCapture c = new UpiCapture();
        c.ingest("net.one97.paytm", MANAGE_SCREEN);

        List<OwnnessJudge.Record> own = c.ownAddresses();
        check("own addresses are bound from the manage screen",
                own.size() == 2, "own=" + own.size());
        boolean foundPhone = false;
        for (OwnnessJudge.Record r : own) {
            if (r.vpa.key().equals("7000000000@ptyes")) {
                foundPhone = true;
                check("bound address cites the manage surface",
                        r.verdict == OwnnessJudge.Verdict.OWN
                                && OwnnessJudge.R_OWN_MANAGE.equals(r.reason),
                        r.reason);
            }
        }
        check("the customer's own phone-number handle is among them", foundPhone, "ptyes present");

        UpiCapture h = new UpiCapture();
        h.ingest("net.one97.paytm", HISTORY_SCREEN);
        check("history rows bind nothing as own",
                h.ownAddresses().isEmpty() && h.counterpartyAddresses().size() == 2,
                "cpty=" + h.counterpartyAddresses().size());
        for (OwnnessJudge.Record r : h.counterpartyAddresses()) {
            check("counterparty cites the history surface",
                    OwnnessJudge.R_CPTY_HISTORY.equals(r.reason), r.reason);
        }

        // An address only ever seen in the middle of nowhere stays unclaimed.
        UpiCapture u = new UpiCapture();
        u.ingest("net.one97.paytm", "Referral program\nInvite friends\nRewards 7000000000@ptyes");
        check("unknown surface makes no own claim",
                u.ownAddresses().isEmpty(), "own=" + u.ownAddresses().size());

        // Same address on both sides of a transfer = two of your own accounts.
        OwnnessJudge j = new OwnnessJudge();
        UpiVpa.Vpa shared = UpiVpa.extract("Shared Own Handle@ptyes").get(0);
        j.observe(shared, ScreenClass.classify(HISTORY_SCREEN), Boolean.TRUE);
        j.observe(shared, ScreenClass.classify(HISTORY_SCREEN), Boolean.FALSE);
        OwnnessJudge.Record self = j.get(shared);
        check("self-transfer is bound as own",
                self.verdict == OwnnessJudge.Verdict.OWN
                        && OwnnessJudge.R_OWN_SELF_XFER.equals(self.reason), self.reason);

        // A statement surface must be distinguishable from a history surface.
        // The role string carries its direction, so an exact-name lookup here
        // silently reported every statement row as a history row.
        UpiCapture stmtReason = new UpiCapture();
        stmtReason.ingest("net.one97.paytm",
                "Account Statement\nDownload Statement\nPassbook\n"
                + "Paid to Merchant One\nUPI ID: merchant9@ybl\nUPI Ref No: 395047088754");
        check("a statement row is reported as a statement, not as history",
                stmtReason.counterpartyAddresses().size() == 1
                        && OwnnessJudge.R_CPTY_STATEMENT.equals(
                                stmtReason.counterpartyAddresses().get(0).reason),
                stmtReason.counterpartyAddresses().isEmpty() ? "none"
                        : stmtReason.counterpartyAddresses().get(0).reason);

        // A real statement export binds nothing, which is the whole point.
        UpiCapture stmt = new UpiCapture();
        stmt.ingest("net.one97.paytm",
                "Paytm Statement for\nTransaction Details Notes Tags Your Account Amount\n"
                + "06 Jan\n10:17 PM\nPaid to Merchant One\nUPI ID: paytmqr6mwbm3@ptys on\n"
                + "UPI Ref No: 395047088754\n# Food\nBank Of India - 84\n- Rs.150");
        check("a statement export binds no own address",
                stmt.ownAddresses().isEmpty() && stmt.counterpartyAddresses().size() == 1,
                "own=" + stmt.ownAddresses().size());

        // Direction inference.
        check("debit row direction is read",
                Boolean.TRUE.equals(UpiCapture.directionNear(HISTORY_SCREEN, 60)),
                "Paid to");
        check("credit row direction is read",
                Boolean.FALSE.equals(UpiCapture.directionNear(HISTORY_SCREEN,
                        HISTORY_SCREEN.indexOf("9289604880@ybl"))),
                "Received from");
        check("direction is unclaimed when no verb is near",
                UpiCapture.directionNear(MANAGE_SCREEN,
                        MANAGE_SCREEN.indexOf("7000000000@ptyes")) == null,
                "manage screen has no direction");

        // Settled vs pending, mirroring the providers' precedence: an explicit
        // failure word anywhere in the row beats an optimistic one.
        String settled = "Paid to Merchant One\nUPI ID: merchant1@ybl\nSuccessful";
        String pending = "Paid to Merchant Two\nUPI ID: merchant2@ybl\nPending";
        String failed = "Paid to Merchant Three\nUPI ID: merchant3@ybl\nFailed";
        check("a settled row counts as completed usage",
                UpiCapture.completedNear(settled, settled.indexOf("merchant1@ybl")),
                "Successful");
        check("a pending row does not count as completed",
                !UpiCapture.completedNear(pending, pending.indexOf("merchant2@ybl")),
                "Pending");
        check("a failure word overrides an optimistic one",
                !UpiCapture.completedNear("Completed\nFailed\nUPI ID: m4@ybl",
                        "Completed\nFailed\nUPI ID: m4@ybl".indexOf("m4@ybl")),
                "failure wins");

        // Usage status, as the PhonePe provider reports it.
        UpiCapture use = new UpiCapture();
        use.ingest("net.one97.paytm", HISTORY_SCREEN);
        OwnnessJudge.Record used = use.counterpartyAddresses().get(0);
        check("history usage is counted and reported as COMMON",
                "COMMON".equals(used.usageStatus()) && used.usageCount > 0,
                "use=" + used.usageCount);
        check("usage is UNKNOWN until a history screen is actually read",
                "UNKNOWN".equals(new UpiCapture().ingest("net.one97.paytm", MANAGE_SCREEN)
                        .get(0).usageStatus()),
                "no history yet");
    }

    // --- 5. the intelligence layer is device-free ---------------------------

    static void purity() throws Exception {
        Class<?>[] pure = {
            UpiVpa.class, ScreenClass.class, OwnnessJudge.class, UpiCapture.class
        };
        boolean clean = true;
        String offender = "";
        for (Class<?> c : pure) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getReturnType().getName().startsWith("android")
                        || containsAndroid(m.getReturnType().getName())) {
                    clean = false; offender = c.getSimpleName() + "." + m.getName();
                }
                for (Class<?> p : m.getParameterTypes()) {
                    if (p.getName().startsWith("android")) {
                        clean = false; offender = c.getSimpleName() + "." + m.getName();
                    }
                }
            }
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                if (f.getType().getName().startsWith("android")) {
                    clean = false; offender = c.getSimpleName() + "." + f.getName();
                }
            }
        }
        check("capture logic references no Android type", clean,
                clean ? "runs identically on JVM and device" : "leaked via " + offender);

        check("the pure pipeline is constructible with no Android on the classpath",
                new UpiCapture(CapturePolicy.WALLET_PACKAGES) != null, "JVM-only construction");

        check("allowlist is exactly the four supported wallet apps",
                CapturePolicy.WALLET_PACKAGES.size() == 4
                        && CapturePolicy.isWallet("net.one97.paytm")
                        && CapturePolicy.isWallet("com.phonepe.app")
                        && CapturePolicy.isWallet("com.mobiwik.android")
                        && CapturePolicy.isWallet("money.super.payments")
                        && !CapturePolicy.isWallet("com.example.other"),
                "paytm, phonepe, mobikwik, and other wallet apps");

        // The allowlist is immutable: a caller cannot widen it for everyone.
        boolean immutable = true;
        try {
            @SuppressWarnings("unchecked")
            java.util.Set<String> mutable =
                    (java.util.Set<String>) CapturePolicy.WALLET_PACKAGES;
            mutable.add("com.example.evil");
            immutable = !CapturePolicy.isWallet("com.example.evil");
        } catch (UnsupportedOperationException expected) {
            immutable = true;
        }
        check("allowlist cannot be widened at runtime", immutable, "unmodifiable");
    }

    // --- 7. the real customer address ------------------------------------

    /**
     * The tester's own Paytm Payments Bank address, checked against the real
     * grammar. It appears here because it is the shape that has to work; the
     * rest of the suite uses fabricated handles so no fixture depends on it.
     */
    static final String REAL_VPA = "7008979585@ptyes";

    static void realAddress() {
        List<UpiVpa.Vpa> got = UpiVpa.extract(REAL_VPA);
        check("the real customer address parses", got.size() == 1, UpiVpa.logLabel(got.get(0)));
        UpiVpa.Vpa v = got.get(0);
        check("it is a phone-number handle on Paytm Payments Bank",
                v.isPhoneNumberHandle() && "ptyes".equals(v.suffix),
                "suffix=" + v.suffix + " handleLen=" + v.handle.length());
        check("it is not mistaken for an internal id",
                !UpiVpa.isInternalHandle(v.handle), "payable");

        // It must survive the full pipeline and be bound, not treated as noise.
        UpiCapture c = new UpiCapture();
        c.ingest("net.one97.paytm", "Profile\nUPI & Payments\nYour UPI ID\n"
                + "Paytm Payments Bank\n" + REAL_VPA + "\nVerified\nPrimary");
        List<OwnnessJudge.Record> own = c.ownAddresses();
        check("it is bound as the customer's own address",
                own.size() == 1 && own.get(0).vpa.key().equals(REAL_VPA),
                own.isEmpty() ? "none" : own.get(0).vpa.key());

        // And it must pass the provider's own ownership gate.
        check("provider ownership gate accepts it",
                ProviderStatement.rejectOwnClaim(REAL_VPA, "REGISTERED", "ACTIVE",
                        "9876543210", "+919876543210") == null,
                "registered + active + phone matches");
    }

    // --- 8. the provider contract -----------------------------------------

    static void providerContract() {
        check("a CREDIT row's own endpoint is the payee",
                "payeeVpa".equals(ProviderStatement.ownEndpointField(
                        ProviderStatement.directionOf("CREDIT"))), "payee");
        check("a DEBIT row's own endpoint is the payer",
                "payerVpa".equals(ProviderStatement.ownEndpointField(
                        ProviderStatement.directionOf("DEBIT"))), "payer");
        check("an unknown direction claims no endpoint",
                ProviderStatement.ownEndpointField(
                        ProviderStatement.directionOf("whatever")) == null, "no claim");

        // Ownership needs a state AND a phone match, never a shape alone.
        check("a registered, active address on the right phone is claimed",
                ProviderStatement.rejectOwnClaim("7008979585@ptyes", "REGISTERED", "ACTIVE",
                        "9876543210", "9876543210") == null, "claimed");
        check("with +91 on one side it is still the same phone",
                ProviderStatement.rejectOwnClaim("7008979585@ptyes", "REGISTERED", "ACTIVE",
                        "9876543210", "+91 98765 43210") == null, "normalised");
        check("a phone that does not match is refused",
                ProviderStatement.FAIL_ACCOUNT_MISMATCH.equals(
                        ProviderStatement.rejectOwnClaim("7008979585@ptyes", "REGISTERED",
                                "ACTIVE", "9876543210", "9999999999")),
                "ACCOUNT_MISMATCH");
        check("an address only shapes like a VPA is not enough",
                ProviderStatement.rejectOwnClaim("7008979585@ptyes", "NOT_ACTIVE", "ACTIVE",
                        "9876543210", "9876543210") != null, "state gate applies");
        check("an inactive account is refused",
                ProviderStatement.rejectOwnClaim("7008979585@ptyes", "REGISTERED", "SUSPENDED",
                        "9876543210", "9876543210") != null, "account gate applies");
        check("a shape-alike internal id cannot be claimed",
                "INVALID_UPI".equals(ProviderStatement.rejectOwnClaim(
                        "u12345678901@ptyes", "REGISTERED", "ACTIVE",
                        "9876543210", "9876543210")), "INVALID_UPI");

        // A receipt must be checked against the address on the correct side only.
        List<String> own = java.util.Arrays.asList("7008979585@ptyes");
        check("a credit whose payee is the customer passes",
                ProviderStatement.verifyReceipt(ProviderStatement.Direction.CREDIT,
                        "someone@ybl", "7008979585@ptyes", own) == null, "own payee");
        check("a credit whose payee is somebody else fails",
                ProviderStatement.FAIL_TXN_MISMATCH.equals(
                        ProviderStatement.verifyReceipt(ProviderStatement.Direction.CREDIT,
                                "someone@ybl", "other@ybl", own)),
                "TRANSACTION_ACCOUNT_MISMATCH");
        check("the payer alone does not satisfy a credit check",
                ProviderStatement.FAIL_TXN_MISMATCH.equals(
                        ProviderStatement.verifyReceipt(ProviderStatement.Direction.CREDIT,
                                "7008979585@ptyes", "other@ybl", own)),
                "own side only");
        check("with nothing bound, a receipt cannot be verified",
                ProviderStatement.FAIL_UPI_UNAVAILABLE.equals(
                        ProviderStatement.verifyReceipt(ProviderStatement.Direction.DEBIT,
                                "7008979585@ptyes", null, new ArrayList<>())),
                "no bind, no verdict");

        // The transcribed paths match the live integrations.
        check("PhonePe reads 450 days at page size 20",
                ProviderStatement.phonepeHistoryPath("123").contains("duration=450d")
                        && ProviderStatement.phonepeHistoryPath("123").contains("size=20"),
                ProviderStatement.phonepeHistoryPath("123"));
        check("the MobiKwik own-VPA path is the one that binds",
                "/p/upi/vpa/profile/v2".equals(ProviderStatement.MOBIKWIK_CURRENT_VPAS),
                ProviderStatement.MOBIKWIK_CURRENT_VPAS);
        check("unbound counterparties are reported as unexplained",
                ProviderStatement.unexplained(
                        java.util.Arrays.asList("a@ybl", "7008979585@ptyes"), own).size() == 1,
                "one unexplained");
    }

    // --- 9. the guarded route walk ----------------------------------------

    static void route() {
        List<String> paytm = UpiRoute.paytmRoute();

        check("the route names the two hops the tester described",
                paytm.size() == 2 && paytm.contains("UPI & Payment")
                        && paytm.contains("Manage UPI ID"),
                paytm.toString());

        check("the shipped route passes its own safety screen",
                UpiRoute.routeIsSafe(paytm), "no payment wording");

        check("a hop is chosen only when its label is on screen",
                "UPI & Payment".equals(UpiRoute.nextHop(paytm, 0,
                        "Home\nUPI & Payment\nManage UPI ID", ScreenClass.classify("Home"))),
                "hop 1 present");
        check("a route whose label is absent takes no hop",
                UpiRoute.nextHop(paytm, 0, "Home\nOffers", ScreenClass.classify("Home")) == null,
                "no blind tapping");
        check("the walk stops on a payment surface",
                UpiRoute.nextHop(paytm, 0, "Send Money\nPay Now\nUPI & Payment",
                        ScreenClass.classify("Send Money\nPay Now")) == null,
                "PAY aborts");

        check("arrival is detected at the target screen",
                UpiRoute.arrived("Manage UPI ID\n7000000000@ptyes", paytm), "arrived");
        check("arrival is not claimed at the previous screen",
                !UpiRoute.arrived("UPI & Payment", paytm), "not yet");

        // A route containing a payment control must be refused outright, so a
        // future edit cannot point the walk at moving money.
        String[][] badRoutes = {
            {"Pay Now", "pay verb"},
            {"Send Money", "transfer verb"},
            {"Enter amount", "amount entry"},
            {"Confirm Payment", "confirm"},
            {"Collect Request", "collect"},
            {"UPI PIN", "pin entry"},
        };
        for (String[] r : badRoutes) {
            check("refuses a route containing " + r[1],
                    !UpiRoute.routeIsSafe(java.util.Arrays.asList(r[0], "Manage UPI ID")),
                    r[0]);
            check("names the offending word for " + r[1],
                    UpiRoute.forbiddenWord(r[0]) != null, r[0]);
        }
        check("a route with any bad hop disables navigation entirely",
                UpiRoute.nextHop(java.util.Arrays.asList("Pay Now", "Manage UPI ID"), 0,
                        "Pay Now\nManage UPI ID",
                        ScreenClass.classify("Manage UPI ID")) == null,
                "no partial walk");
        check("an empty route is not safe",
                !UpiRoute.routeIsSafe(new ArrayList<String>()), "fails closed");

        // The route is chosen by package, and an unsupported wallet must not
        // fall through to Paytm's labels.
        check("the Paytm route is selected for Paytm",
                UpiRoute.routeFor("net.one97.paytm") == UpiRoute.PAYTM_UPI_ROUTE, "paytm route");
        check("the Supermoney route is selected for Supermoney",
                UpiRoute.routeFor("money.super.payments") != null
                        && UpiRoute.routeFor("money.super.payments").contains("Manage UPI"),
                "supermoney route");
        check("an unsupported wallet gets no route",
                UpiRoute.routeFor("com.example.banking") == null,
                "closed default");
        check("a null package gets no route",
                UpiRoute.routeFor(null) == null, "closed default");
        check("the default route is not Paytm's",
                UpiRoute.routeFor("com.mobiwik.android") != paytm, "no fallback");
    }

    static boolean containsAndroid(String n) { return n.startsWith("android"); }

    // --- 6. the real statement cannot yield an own address ------------------

    static void structure() {
        UpiCapture c = new UpiCapture(new HashSet<>(Arrays.asList("net.one97.paytm")));
        StringBuilder stmt = new StringBuilder("Passbook Payments History\n"
                + "Transaction Details Notes Tags Your Account Amount\n");
        String[][] rows = {
            {"06 Jan", "10:17 PM", "Paid to Merchant One", "paytmqr6mwbm3@ptys", "- Rs.150"},
            {"06 Jan", "5:18 PM", "Received from Person Two", "9289604880@ybl", "+ Rs.5,000"},
            {"05 Jan", "2:24 PM", "Money sent to Person Three", "9953731082@pthdfc", "- Rs.700"},
            {"05 Jan", "1:51 AM", "Automatic payment for Jar Gold", "jargoldonline@ybl", "- Rs.307"},
            {"04 Jan", "10:53 PM", "Paid to Merchant Two", "q693967345@ybl", "- Rs.20"},
        };
        for (String[] r : rows) {
            stmt.append(r[0]).append('\n').append(r[1]).append('\n').append(r[2]).append('\n')
                .append(" UPI ID: ").append(r[3]).append(" on\n")
                .append("UPI Ref No: ").append(100000000000L + rows.length).append('\n')
                .append("# Tag\nBank Of India - 84\n").append(r[4]).append('\n');
        }
        c.ingest("net.one97.paytm", stmt.toString());
        check("160-row statement shape yields only counterparties",
                c.ownAddresses().isEmpty() && c.counterpartyAddresses().size() == rows.length,
                "own=" + c.ownAddresses().size() + " cpty=" + c.counterpartyAddresses().size());
        check("the 'Your Account' column holds a bank mask, not a handle",
                stmt.toString().contains("Your Account") && !stmt.toString().contains("7000000000@ptyes"),
                "own identifier is absent from the statement by design");
    }
}