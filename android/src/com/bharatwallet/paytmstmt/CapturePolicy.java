package com.bharatwallet.paytmstmt;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Capture policy: the only packages read, and the hard bounds on any capture.
 *
 * <p>Pure {@code java.util} with no Android imports, so the enforcement rules
 * are testable on the JVM rather than only observable on a device. Both the
 * Android service and the pure pipeline read their limits from here, which is
 * what keeps them from drifting apart.
 */
public final class CapturePolicy {

    /**
     * The wallet packages whose UPI screens may be read. Nothing outside this
     * set is ever read, so the pilot cannot become a general screen reader.
     *
     * <p>Every entry here must also appear in the manifest's {@code <queries>}
     * block. On Android 11+ an app that targets a recent SDK cannot see another
     * package at all unless it declares it, and an undeclared package looks
     * exactly like one that is not installed — which is how Paytm came to be
     * reported as absent on a phone that had it installed the whole time.
     */
    public static final Set<String> WALLET_PACKAGES = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    "net.one97.paytm",       // Paytm
                    "com.phonepe.app",       // PhonePe
                    "com.mobiwik.android",   // MobiKwik
                    "money.super.payments"  // Supermoney
            )));

    /** Packages that may also be read: this app's own verification harness. */
    public static final Set<String> TRUSTED_PACKAGES;

    static {
        Set<String> t = new HashSet<>(WALLET_PACKAGES);
        t.add(UpiCaptureServicePackage.NAME);
        TRUSTED_PACKAGES = Collections.unmodifiableSet(t);
    }

    /** Cap on nodes visited per event, so a huge list cannot stall the UI thread. */
    public static final int MAX_NODES = 4000;

    /** Cap on screen text retained per event, in characters. */
    public static final int MAX_TEXT = 24000;

    private CapturePolicy() { }

    public static boolean mayRead(String pkg) {
        return pkg != null && TRUSTED_PACKAGES.contains(pkg);
    }

    /** True for the wallet apps the pilot is aimed at, excluding the harness. */
    public static boolean isWallet(String pkg) {
        return pkg != null && WALLET_PACKAGES.contains(pkg);
    }
}

/** Holder for this app's own package name, kept out of CapturePolicy's imports. */
final class UpiCaptureServicePackage {
    static final String NAME = "com.bharatwallet.paytmstmt";

    private UpiCaptureServicePackage() { }
}