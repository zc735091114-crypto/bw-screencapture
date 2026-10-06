// ILabService.aidl
//
// The lab feeder's IPC contract.
//
// SHAPE PARITY, IDENTITY SEPARATION
// The method set and transaction-code ordering intentionally mirror the
// LongFaFaPay contract (com.longfafa.pay.IPayService):
//
//   tx 1  ping()                          tx 5  getUPIList()
//   tx 2  onEvent(a, b, c)                tx 6  getRequestMeta()
//   tx 3  setLabCallback(cb)              tx 7  getPayListByTimeStamp(start, end)
//   tx 4  getPayList(page, pageSize)      tx 8  getUPIRequestMeta()
//
// getLabDiagnostics() is appended (tx 9) to carry the equivalent of
// com.coin.ipc.IService.getUPIDiagnostics.
//
// The identity is entirely our own and is the point of the exercise:
//   descriptor  com.bharatwallet.lab.ILabService  (not com.coin.ipc.IService)
//   action      com.bharatwallet.lab.BIND_SERVICE
//   package     com.bharatwallet.lab.feeder
//
// Nothing here impersonates com.phonepe.app or com.longfafa.pay. See
// lab-feeder/README.md for why that boundary is load-bearing.
//
// EVERY RESPONSE IS SYNTHETIC. No real VPA, merchant MID, session token or
// transaction reference is ever produced or accepted. Payloads are tagged
// source="LAB_FEEDER" and providerAuthorized=false. See LabFixtures.java.

package com.bharatwallet.lab;

import com.bharatwallet.lab.ILabCallback;

interface ILabService {

    // Liveness probe. Returns a small JSON envelope, never an empty string.
    String ping();

    // Client-driven event injection, kept for interface parity with the
    // reference contracts. The feeder records it and echoes the result.
    String onEvent(String event, String key, String value);

    // Register the client's push channel. Passing null clears it.
    String setLabCallback(ILabCallback callback);

    // Page through synthetic transactions. page is 1-based; pageSize is
    // clamped to [1, 200].
    String getPayList(int page, int pageSize);

    // The account holder's OWN UPI identifiers, as they would come from a real
    // provider's profile data. Here they are fixtures in a reserved domain.
    String getUPIList();

    // Non-sensitive request metadata (fixture build, schema version, counts).
    String getRequestMeta();

    // Time-windowed read, used to exercise incremental history logic.
    // Bounds are epoch milliseconds, inclusive.
    String getPayListByTimeStamp(long startTimeMillis, long endTimeMillis);

    // Metadata about the UPI list specifically (fixture id, count, domain).
    String getUPIRequestMeta();

    // Diagnostics: bind count, callback state, fixture inventory.
    String getLabDiagnostics();
}