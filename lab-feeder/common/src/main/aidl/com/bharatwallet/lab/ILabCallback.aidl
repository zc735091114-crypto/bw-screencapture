// ILabCallback.aidl
//
// Asynchronous push channel for the lab feeder. Mirrors the shape of
// com.coin.ipc.ICallback and com.longfafa.pay.IPayBack: a single one-way
// onEvent that the host invokes whenever it has something to report without
// waiting for the client to ask.
//
// Transaction code 1 matches both reference interfaces, so a client written
// against either reference shape behaves identically here.
//
// The arguments are positional and untyped by design (that is how the
// reference interfaces carry them). Meaning per call site is documented in
// LabService.java:
//   ("stage",     stageName,        detail)
//   ("diagnostic",key,              value)

package com.bharatwallet.lab;

interface ILabCallback {
    oneway void onEvent(String event, String key, String value);
}