# Corrections register

> **Provenance:** copied into this repository from the owner's workspace.
> Filesystem paths named in this document (e.g. `reverse-analysis/...`,
> `QWEN-HANDOFF-...md`) are relative to that workspace and are **not**
> present in this repository.

---


Authoritative record of claims that were made in this workspace and have since
been **withdrawn or corrected**, with the evidence. Where a document still
contains an old claim, it is struck through in place and points here.

Purpose: so a corrected claim is not silently re-asserted later, and so anyone
reading an older report knows to check here first.

---

## C1 — "Paytm requires an account ≥30 days old for a 30-day statement"

**Status: WITHDRAWN. Disproven by a real device test.**

**Date recorded:** 2026-10-03 (as a second-hand tester report)
**Date withdrawn:** 2026-10-06

**Original wording** (`QWEN-HANDOFF-Paytm-Binding-Review-20261003.md`, marked
there as a human report rather than logged API evidence):

> Paytm requires an account ≥30 days old for a 30-day statement.

**Why withdrawn:** a real Indian-device test did **not** reproduce it. The
constraint does not exist as stated, and repeating it wrongly closes off a
usable path.

**Corrected position:** there is **no 30-day account-age prerequisite** for a
Paytm statement. Do not plan around it.

**Files corrected in place:**
- `QWEN-HANDOFF-Paytm-Binding-Review-20261003.md`
- `UPI-Bind-Source-Truth-and-Postman-PLAN-20261003.md` (two occurrences)

**Not affected:** `BW-Complete-Change-History-Through-1.0.122-20260926.md`
contains "30-day" only in reference to the Cloudflare Access session duration
(`access-session-30d.json`), which is a different thing and remains correct.

---

## C2 — "TopPay does not implement Paytm or SuperMoney login"

**Status: WITHDRAWN as too broad.**

**Date recorded:** 2026-10-06 (static review)
**Date withdrawn:** 2026-10-06, in response to review feedback that was correct

**Original wording** (`reverse-analysis/ALL-APK-STATIC-REVIEW.md`):

> The main finding: TopPay does not implement Paytm or SuperMoney login.

**Why withdrawn:** it over-read the evidence. A static review of the Binder
plugins and the Cordova layer cannot see a server-dispatched execution path, and
there is one.

**Counter-evidence** (`reverse-analysis/toppay/sources/com/xpay/mqtt/`):

```
MqttCrawlerService.java:695   task message received on the task topic
MqttCrawlerService.java:699   taskId read from the payload
MqttCrawlerService.java:711   executeHttpTask(json) invoked
MqttCrawlerService.java:937   request built from task fields and executed on device
MqttCrawlerService.java:1255  result published to the result topic
```

`executeHttpTask` constructs a request from task-supplied `url`/`requestUrl`,
`method` (default `GET`), headers, body, `timeout` and `followRedirects`, and
executes it **on the phone**. Topics (`taskTopic`, `resultTopic`,
`heartbeatTopic`, `deviceOnlineTopic`, `noticeTopic`) are configured at runtime
(`MqttCrawlerService.java:479`).

**Corrected position:**

> The reviewed material does not establish the complete upstream authentication
> implementation. The absence of an official SDK does not exclude backend
> integration or phone-executed requests.

**Files corrected in place:**
- `reverse-analysis/ALL-APK-STATIC-REVIEW.md`
- `reverse_apk.md` already documented the server-driven HTTP task surface in its
  sequence diagram and needed no change.

---

## C3 — "SuperMoney is unsupported" (scoping error)

**Status: SCOPING CORRECTED.**

**Original claim:** SuperMoney is unsupported / has no client.

**Corrected position:** two different statements were being conflated.

- **SuperMoney has no public API** for transaction history. That remains true.
- **TopPay nevertheless registers SuperMoney as a first-class bind type.** In
  `reverse-analysis/toppay/resources/assets/www/static/js/index-58cda882.js`:
  `case 17: P="super_money"`.

So "unsupported" describes SuperMoney's public API surface, not this
application. The app supports it; the API does not.

**BW harness behaviour is unchanged and still correct:** SuperMoney returns
`501 PROVIDER_NOT_IMPLEMENTED` because *our* pilot has no browser client for it,
which is a statement about our code, not about SuperMoney or TopPay.

---

## C4 — "/bind/* endpoints do not exist" (implicit)

**Status: CORRECTED.**

The `/bind/pre/check`, `/bind/send/otp`, `/bind/check/otp` and
`/bind/select/upi` endpoints are real, but they are **not** in the Cordova
plugins or the Binder interfaces — they are in TopPay's own web frontend bundle:

```
reverse-analysis/toppay/resources/assets/www/static/js/index-58cda882.js
```

`requestId` is the correlation key, minted by the backend at `/bind/pre/check`
and carried by the client through `send/otp` and `check/otp`. `upiInfos` is
populated from **backend responses**, not generated client-side.

**Important evidence gap, still open:** `requestId` does **not** appear anywhere
in the `com.xpay` sources, so nothing observed shows it reaching the phone. The
executor's only correlation key is `taskId`. Whether an MQTT task is bound to a
specific bind attempt **cannot be determined from the APK** — the backend is
required.

---

## C5 — MQTT described as heartbeat/reconnection only

**Status: CORRECTED** (covered by C2).

`MqttCrawlerService` performs heartbeat **and** server-dispatched HTTP
execution. Reading only the first behaviour understated the surface.

Related observation from the same file, unchanged and worth keeping visible:
`HARDCODED_CERTIFICATE_PINS = new String[0][]` (`MqttCrawlerService.java:90`) —
the pinning machinery exists but the pin table is empty, so tasks execute with
certificate pinning effectively disabled. Whether that is deliberate rotation or
a defect cannot be determined statically.

---

## Standing positions (not corrections, but relevant to avoid re-litigating)

These are recorded here because they keep coming up.

**P1 — No Paytm impersonation client.** An app presenting itself as the official
Paytm app to authenticate against Paytm's servers is impersonation of a third
party and will not be built. It also fails on its own terms: Paytm login-init
returns **HTTP 400 / BE1423007 on `x-int-token`**, a runtime integrity token
that cannot be synthesized from phone model, device IDs or SIM data
(`UPI-Bind-Source-Truth-and-Postman-PLAN-20261003.md`). Disguising the client
changes presentation, not the integrity layer, so it buys nothing.

**P2 — No session credential extraction.** The on-device capturer reads UPI
handles and statement text from a screen the account holder is already logged
into. It does not read cookies, tokens or auth material, and the built APK has
no INTERNET permission. A Paytm session is exactly what would be needed to make
requests as the user; extracting one is P1 by another route. What the milestone
requires is a fact ("this VPA is mine"), which is what `OwnnessJudge` returns.

**P3 — The milestone stands.** *authorized login → verified own UPI → one
existing real incoming transaction.* Merchant payment APIs and simulated
callbacks do not satisfy it. Neither does a self-hosted fixture: the lab feeder
returns synthetic rows and is structurally prevented from making `ready` true
(`test_feeder_is_not_a_probe_source`).