# bw-lab-feeder — on-device AIDL fixture

**Status: integration test fixture. Not a provider. Not acceptance evidence.**

Date: 6 October 2026. Written for the bw-screencapture harness.

---

## 1. What this is

A small Android service that exports a Binder interface returning **synthetic**
UPI identifiers and transaction rows, so the BW client can exercise a real bind
lifecycle, real AIDL marshalling and real history-windowing logic without a
provider account.

It reproduces the architecture used by the TopPay and LongFaFaPay Cordova
plugins — bind by package + action + service class, verify the host's signing
digest, forward calls, push async events through a registered callback.

## 2. What this is not

It is not a provider adapter. Its rows are synthetic and its output must never
be recorded as a successful consumer bind.

The reason this boundary is enforced rather than merely documented:

> Do not label a manually pasted UPI, a downloaded statement, or a
> merchant-order response as a successful consumer bind.
> — `BW-Complete-Change-History-Through-1.0.122-20260926.md`

A fixture that can be mistaken for a real read is worse than no fixture, because
it lets a payment platform advance on evidence that does not exist.

## 3. Identity separation

The AIDL **shape** is mirrored from the reference contracts. The **identity** is
entirely ours. This is the part that is not negotiable.

| | Reference contracts | bw-lab-feeder |
|---|---|---|
| AIDL descriptor | `com.coin.ipc.IService`, `com.longfafa.pay.IPayService` | `com.bharatwallet.lab.ILabService` |
| Callback | `com.coin.ipc.ICallback`, `com.longfafa.pay.IPayBack` | `com.bharatwallet.lab.ILabCallback` |
| Bind action | `com.coin.BIND_SERVICE`, `com.longfafa.pay.BIND_SERVICE` | `com.bharatwallet.lab.BIND_SERVICE` |
| Host package | `com.phonepe.app`, `com.longfafa.pay` | `com.bharatwallet.lab.feeder` |
| Service class | `com.coin.ipc.LabService`, `com.longfafa.pay.PayService` | `com.bharatwallet.lab.feeder.LabService` |

`com.phonepe.app` is PhonePe (One97) and `com.longfafa.pay` is a third-party
app. Neither is ours. Reusing their identity would make fabricated rows
indistinguishable from a genuine provider read — which is the exact failure
mode above. `com.topPay.app` (TopPay 1.0.5) is a *client*; its manifest declares
no `com.coin.ipc.*` service, only the intent-filter action string, which is why
it appears in the reference column as a caller rather than a host.

## 4. AIDL surface

Transaction-code ordering mirrors `IPayService` (1–8) so a client written
against that contract behaves identically, plus one appended diagnostic call.

```java
interface ILabService {
    String ping();                                    // tx 1
    String onEvent(String a, String b, String c);     // tx 2
    String setLabCallback(ILabCallback cb);           // tx 3
    String getPayList(int page, int pageSize);        // tx 4
    String getUPIList();                              // tx 5
    String getRequestMeta();                          // tx 6
    String getPayListByTimeStamp(long start, long e); // tx 7
    String getUPIRequestMeta();                       // tx 8
    String getLabDiagnostics();                       // tx 9  (≈ IService.getUPIDiagnostics)
}

interface ILabCallback {
    oneway void onEvent(String event, String key, String value);  // tx 1
}
```

Error codes in `LabBinderClient` deliberately mirror the reference taxonomy:
`SERVICE_DISCONNECTED`, `REMOTE_ERROR`, `UNSUPPORTED_ACTION`, `INVALID_ARGS`.

## 5. Why the data cannot be mistaken for real

Three properties, each enforced in code and covered by a gate.

**Reserved domain.** Every address is `@bwlab.invalid` or `@lab.invalid`.
`.invalid` is reserved by RFC 2606 and can never be registered or resolved in
the public UPI namespace. There is no flag, seed or hidden mode that emits a
routable address.

**Self-identifying envelope.** Every response carries:

```json
{
  "source": "LAB_FEEDER",
  "providerAuthorized": false,
  "synthetic": true,
  "acceptanceEligible": false
}
```

**Hostile-by-default verification.** `_require_synthetic()` in
`src/provider_base.py` refuses any payload that does not carry *all four* flags
with exact values, using `is not False` / `is not True` rather than truthiness.
A payload that omits a flag is rejected. This is deliberate: a permissive
default would mean a future change to the feeder could silently start claiming
authorization.

The Android client applies the same check independently
(`LabBinderClient.isAcceptableProvenance`), so a compromised or misconfigured
feeder still cannot pass provenance at the consumer.

## 6. Acceptance eligibility is structurally impossible

`LabFeederProvider` is **not** a `ConsumerBrowserProvider`. It cannot open a
browser, and its host allowlist is permanently empty. Every result is stamped
`acceptanceEligible=False` by `_stamp()` even if the upstream payload dropped
the field.

The load-bearing gate is `test_feeder_is_not_a_probe_source`: with a working
transport registered and a successful read available, `load_probe()` still raises
`PROBE_MISSING`. The feeder has no write path to the probe artifact, so
`ready: true` can never be satisfied by synthetic data.

`SUPPORTED_PROVIDER_NAMES` deliberately excludes the feeder, so a
`501 PROVIDER_NOT_IMPLEMENTED` message can never imply it is a provider.

## 7. Layout

Three Gradle modules:

```
lab-feeder/
  settings.gradle          pluginManagement + FAIL_ON_PROJECT_REPOS
  build.gradle             AGP 8.11.0 declared, applied per module
  gradle.properties
  local.properties         sdk.dir (git-ignored)
  common/                  android library
    build.gradle
    src/main/aidl/com/bharatwallet/lab/{ILabService,ILabCallback}.aidl
    src/main/java/com/bharatwallet/lab/feeder/client/LabBinderClient.java
  feeder/                  application  com.bharatwallet.lab.feeder
    build.gradle
    src/main/AndroidManifest.xml
      <permission com.bharatwallet.lab.BIND_FEEDER protectionLevel="signature">
      <service .LabService exported=true permission=...>
    src/main/java/com/bharatwallet/lab/feeder/{LabService,LabFixtures}.java
  probe/                   application  com.bharatwallet.lab.probe
    build.gradle
    src/main/AndroidManifest.xml   <queries> + <uses-permission>
    src/main/java/com/bharatwallet/lab/probe/ProbeActivity.java
```

`:common` is shared so both APKs generate the AIDL from one contract. That is
how a cross-package bind works: the AIDL *descriptor string* is the contract,
not class identity — exactly as the reference Binder clients operate.

## 8. Build and device verification

**Built and verified on a OnePlus 9 (LE2113, `869edf70`), 6 October 2026.**

```bash
export JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.20.1/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=~/Library/Android/sdk
cd lab-feeder
./gradlew :feeder:assembleDebug :probe:assembleDebug

adb install -r feeder/build/outputs/apk/debug/feeder-debug.apk
adb install -r probe/build/outputs/apk/debug/probe-debug.apk
adb shell am start -n com.bharatwallet.lab.probe/.ProbeActivity
adb logcat -d -s BwLabProbe BwLabFeeder
```

Result:

```
installed:       true
signature match: true
bound + verified
getUPIList -> 2 own UPI handle(s):  labowner@bwlab.invalid, labowner2@bwlab.invalid
getPayList(page=1,size=5):  count=5  totalCount=24  hasMore=true
getPayListByTimeStamp(last 1h) -> 1 row(s)
inverted window -> INVALID_ARGS   (guard held)
diagnostics: bindCount=1 rowCount=24 inventoryDigest=ec4a468e110ac4b248ee4944
PROBE OK
```

Host side logged `bound; action=com.bharatwallet.lab.BIND_SERVICE` then `unbound`.

Three real defects were found only by running it, all now fixed:

1. **Package visibility (Android 11+).** `getPackageInfo` reported the feeder
   as not installed while `pm list packages` listed it. The probe manifest now
   declares `<queries><package android:name="com.bharatwallet.lab.feeder"/></queries>`.
2. **Permission on the wrong side.** The host was self-declaring
   `<uses-permission>`; the *client* must request it. Moved to the probe.
3. **AIDL Stub mis-declared.** `implements ILabService.Stub` does not compile —
   AIDL generates `abstract class Stub extends Binder`, so it is `extends`.

Both APKs are signed `CN=Android Debug, O=Android, C=US`
(SHA-256 `c54fd20604f5a050…`), which is what makes the signature-level bind
admit the probe. That is a development identity and must never be replaced with
a production key for this fixture.

Python half: `36/36` gates pass (both runners).

## 9. What the feeder does and does not unlock

**Unlocks:** exercising the client bind path, AIDL marshalling, `getUPIList`
parsing, `getPayListByTimeStamp` windowing, pagination and
direction/status filtering, and the provider layer's error handling — all
without a provider account, and CI-testable.

**Does not unlock:** a real account holder's own UPI identifiers or statement
history. That requires the Phase 0.5 probe against a genuinely authorized
provider session. Until that exists, `Paytm` returns `503 PROBE_MISSING` and
`SuperMoney` returns `501 PROVIDER_NOT_IMPLEMENTED`, and both are correct.