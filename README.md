# bw-screencapture

Isolated Paytm/SuperMoney statement harness for Bharat Wallet. Fail-closed by
design: every route requires a feature flag, a bearer token, a written consent
record, and a confirmed probe result before any browser launches.

**Current state: not production-ready, and not claiming to be.** The Paytm path
returns `503 PROBE_MISSING` because the Phase 0.5 probe has not been produced.
That is the correct behaviour, not a bug. See *Acceptance boundaries* below.

## What is here

| Path | Purpose |
|---|---|
| `src/app.py` | FastAPI surface. Route guards, consent, session lifecycle. |
| `src/provider.py` | Paytm consumer-web client. Refuses to launch a browser without a confirmed probe. |
| `src/provider_base.py` | Provider abstraction (PhonePe, MobiKwik, Navi, Amazon, Paytm) + the lab-feeder fixture seam. |
| `src/store.py` | Consent record + Fernet-sealed session store. Never persists an OTP. |
| `test_gates.py` | 36 release gates. |
| `bw-paytm-statement-test.service` | systemd unit. Ships **disabled**; binds `127.0.0.1:18450` only. |
| `lab-feeder/` | On-device AIDL fixture — **a test fixture, not a provider.** See its README. |
| `android/` | Read-only on-device capturer sources (accessibility + MediaProjection). |

## Running the gates

```bash
python3 -m venv .venv && ./.venv/bin/pip install -r requirements-dev.txt 2>/dev/null || true
./.venv/bin/python -m pytest test_gates.py -q     # 36 passed
./.venv/bin/python test_gates.py                  # 36/36 passed
```

Both runners must agree before any deployment.

## Request flow

```
flag on → tester token → written consent → confirmed probe → session → OTP → history
```

Each stage fails closed with a distinct code:

| Condition | Response |
|---|---|
| Flag off | `503 DISABLED` |
| Missing/invalid token | `401 AUTH_REQUIRED` / `401 AUTH_INVALID` |
| No consent record | `403 CONSENT_ABSENT` |
| Probe absent | `503 PROBE_MISSING` |
| Probe not confirmed | `503 PROBE_NOT_CONFIRMED` |
| Unsupported provider (e.g. SuperMoney) | `501 PROVIDER_NOT_IMPLEMENTED` |
| Lab-feeder reply missing its provenance flags | `502 FEEDER_PROVENANCE_REJECTED` |

The 501 for an unsupported provider is decided **before** the probe gate is
consulted, so a caller is never mis told that the probe is missing when the
real reason is that the provider has no client.

## Deployment posture

- Binds `127.0.0.1:18450` only. Not publicly reachable by design.
- The unit file ships `BW_PAYTM_STATEMENT_ENABLED=false`; enabling it is an
  explicit operator act, normally a systemd drop-in.
- Tester credentials are time-boxed: a `bw-screencapture-token-expire.timer`
  removes the token, consent record and session store after a fixed window.
- `private/` and `runtime/` are host state and are git-ignored.

## Acceptance boundaries

These are hard limits, not caveats to be worked around later:

1. **No probe, no read.** A real own-UPI or statement read requires an
   authorized provider session confirmed by the Phase 0.5 probe. Without it,
   `Paytm` returns `503`.
2. **SuperMoney has no client.** It is a consumer UPI app with no transaction
   history API, so it returns `501` permanently in this phase.
3. **The lab feeder is a fixture.** Its rows are synthetic, in RFC 2606
   reserved domains, and every result carries `acceptanceEligible=false`. A
   successful feeder read proves the plumbing works; it is **never** evidence of
   a consumer bind.
4. **A manually pasted UPI, an imported statement file, or a merchant-order
   response is not a successful bind** and must not be recorded as one.
5. **No public route exists to this service.** Exposing it requires a separate,
   explicit decision.

## Not in this repository

The third-party Binder clients examined during analysis (TopPay
`com.topPay.app`, `com.phonepe.app`, `com.longfafa.pay`) are **not** reusable as
provider adapters: TopPay is a client whose manifest declares no `com.coin.ipc.*`
service, and the service implementations live inside apps this project does not
own. A client of the same shape would only demonstrate that another vendor's
Binder can be called — not that an own-UPI read is possible. `lab-feeder/`
mirrors the architecture with our own identity for exactly that reason.