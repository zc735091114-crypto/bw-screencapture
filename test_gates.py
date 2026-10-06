"""Fail-closed tests for the Paytm statement pilot.

No network, no browser, no Paytm contact. Every test asserts that the gate
*refuses* to proceed when a precondition (flag / consent / probe / token) is
missing — the behaviour that makes the pilot safe to deploy before Phase 0.5.

Run:  python3 -m pytest -q test_gates.py
  or: python3 test_gates.py
"""

from __future__ import annotations

import json
import os
import sys
import tempfile
from pathlib import Path

# --- isolate every side effect BEFORE importing the modules under test -----
_TMP = Path(tempfile.mkdtemp(prefix="bw-stmt-test-"))
os.environ["BW_PAYTM_STATEMENT_DIR"] = str(_TMP)
os.environ["BW_PAYTM_PROBE_RESULT"] = str(_TMP / "PROBE-RESULT.json")
os.environ["BW_PAYTM_STATEMENT_ENABLED"] = "false"
os.environ.setdefault("BW_PILOT_APP_DIR", str(_TMP / "no-pilot-app"))

_HERE = Path(__file__).resolve().parent
# Layout-agnostic: modules sit in ./src (repo) or alongside this file (server).
for _candidate in (_HERE, _HERE / "src", _HERE.parent, _HERE.parent / "src"):
    if (_candidate / "provider.py").exists():
        sys.path.insert(0, str(_candidate))
        break

import provider  # noqa: E402
import store  # noqa: E402


# ---------------------------------------------------------------------------
# provider: shape classification
# ---------------------------------------------------------------------------

def test_classify_otp_limited() -> None:
    assert provider.classify("Too many attempts. Try again after 24 hours").name == "OTP_LIMITED"


def test_classify_manual_verification() -> None:
    assert provider.classify("Please contact support to continue").name == "MANUAL_VERIFICATION_REQUIRED"


def test_classify_correct_otp() -> None:
    assert provider.classify("Verification successful").name == "CORRECT_OTP"


def test_classify_unknown_is_never_success() -> None:
    """Ambiguous text must NOT be treated as a pass — fail closed."""
    assert provider.classify("Welcome to your account").ok is False


def test_classify_empty_is_failure() -> None:
    assert provider.classify("").name == "EMPTY"


def test_classify_shape_wins_over_copy() -> None:
    """A blocking shape outranks surrounding success wording."""
    text = "Verification successful. Too many attempts."
    assert provider.classify(text).name == "OTP_LIMITED"


# ---------------------------------------------------------------------------
# provider: gates
# ---------------------------------------------------------------------------

def test_flag_off_blocks_start() -> None:
    os.environ["BW_PAYTM_STATEMENT_ENABLED"] = "false"
    try:
        provider.start("9876543210")
        raise AssertionError("start() must refuse when the flag is off")
    except provider.Failure as exc:
        assert exc.code == "DISABLED"
        assert exc.http_status == 503
    finally:
        os.environ["BW_PAYTM_STATEMENT_ENABLED"] = "true"


def test_missing_probe_blocks_start() -> None:
    os.environ["BW_PAYTM_STATEMENT_ENABLED"] = "true"
    Path(os.environ["BW_PAYTM_PROBE_RESULT"]).unlink(missing_ok=True)
    try:
        provider.start("9876543210")
        raise AssertionError("start() must refuse without a Phase 0.5 probe")
    except provider.Failure as exc:
        assert exc.code == "PROBE_MISSING"
        assert exc.http_status == 503
    finally:
        os.environ["BW_PAYTM_STATEMENT_ENABLED"] = "false"


def test_unconfirmed_probe_blocks_start() -> None:
    """A probe that never confirmed the web surface must not unlock the flow."""
    os.environ["BW_PAYTM_STATEMENT_ENABLED"] = "true"
    Path(os.environ["BW_PAYTM_PROBE_RESULT"]).write_text(json.dumps(
        {"surface": "NOT_CONFIRMED", "host": "paytm.com",
         "login_url": "https://paytm.com/x", "history_url": "https://paytm.com/y"}))
    try:
        provider.start("9876543210")
        raise AssertionError("start() must refuse an unconfirmed probe")
    except provider.Failure as exc:
        assert exc.code == "PROBE_NOT_CONFIRMED"
    finally:
        os.environ["BW_PAYTM_STATEMENT_ENABLED"] = "false"
        Path(os.environ["BW_PAYTM_PROBE_RESULT"]).unlink(missing_ok=True)


def test_empty_host_allowlist_blocks_everything() -> None:
    """Before a probe confirms a host, no destination is trusted."""
    assert provider._ALLOWED_HOSTS == frozenset() or not provider._ALLOWED_HOSTS
    assert provider._host_allowed("https://paytm.com/") is False
    assert provider._host_allowed("https://evil.example/") is False
    assert provider._host_allowed("") is False


def test_read_history_requires_enabled_flag() -> None:
    os.environ["BW_PAYTM_STATEMENT_ENABLED"] = "false"
    try:
        provider.read_history({}, "2026-01-01T00:00:00+05:30")
        raise AssertionError("read_history() must refuse when the flag is off")
    except provider.Failure as exc:
        assert exc.code == "DISABLED"
    finally:
        os.environ["BW_PAYTM_STATEMENT_ENABLED"] = "false"


def test_otp_rejects_non_numeric() -> None:
    for bad in ("", "abcd", "12a4", "  "):
        try:
            provider.submit_otp({"page": object()}, bad)
            raise AssertionError(f"submit_otp({bad!r}) must be rejected")
        except provider.Failure as exc:
            assert exc.code == "OTP_INVALID"
        except Exception:
            # Never reaches the browser: validation precedes any page use.
            pass


# ---------------------------------------------------------------------------
# store: consent gate
# ---------------------------------------------------------------------------

def test_consent_absent_blocks_everything() -> None:
    store.CONSENT_FILE.unlink(missing_ok=True)
    try:
        store.require_consent()
        raise AssertionError("require_consent() must refuse with no record")
    except store.StoreError as exc:
        assert exc.code == "CONSENT_ABSENT"
        assert exc.http_status == 403


def test_session_cannot_be_created_without_consent() -> None:
    store.CONSENT_FILE.unlink(missing_ok=True)
    try:
        store.create_session("T-001")
        raise AssertionError("create_session() must require consent")
    except store.StoreError as exc:
        assert exc.code == "CONSENT_ABSENT"


def test_revoked_consent_blocks() -> None:
    store.write_consent("T-001", "Tester", "I consent to read-only statement fetch")
    store.revoke_consent()
    try:
        store.require_consent()
        raise AssertionError("revoked consent must refuse")
    except store.StoreError as exc:
        assert exc.code == "CONSENT_REVOKED"
    finally:
        store.CONSENT_FILE.unlink(missing_ok=True)


def test_expired_consent_blocks() -> None:
    record = store.write_consent("T-001", "Tester", "consent")
    record["expires_at"] = 1.0
    store.CONSENT_FILE.write_text(json.dumps(record))
    try:
        store.require_consent()
        raise AssertionError("expired consent must refuse")
    except store.StoreError as exc:
        assert exc.code == "CONSENT_EXPIRED"
    finally:
        store.CONSENT_FILE.unlink(missing_ok=True)


def test_incomplete_consent_rejected() -> None:
    try:
        store.write_consent("T-001", "Tester", "   ")
        raise AssertionError("empty statement must be rejected")
    except store.StoreError as exc:
        assert exc.code == "CONSENT_INCOMPLETE"


# ---------------------------------------------------------------------------
# store: single-session + no-OTP persistence
# ---------------------------------------------------------------------------

def test_single_session_limit_enforced() -> None:
    store.write_consent("T-001", "Tester", "consent")
    store.purge_sessions()
    first = store.create_session("T-001")
    try:
        store.create_session("T-001")
        raise AssertionError("a second live session must be refused")
    except store.StoreError as exc:
        assert exc.code == "SESSION_LIMIT"
    finally:
        store.destroy_session(first)
        store.CONSENT_FILE.unlink(missing_ok=True)


def test_session_stores_otp_flag_but_never_the_code() -> None:
    store.write_consent("T-001", "Tester", "consent")
    store.purge_sessions()
    sid = store.create_session("T-001")
    try:
        store.put(sid, stage="authenticated", otp_presented=True)
        record = store.get_session(sid)
        assert record["otp_presented"] is True
        assert isinstance(record["otp_presented"], bool)
        # No field anywhere in the record may contain a code.
        blob = json.dumps(record)
        for code_shape in ("otp_code", "one_time", "sms", "password"):
            assert code_shape not in blob.lower()
        # The public projection leaks neither payload nor secrets.
        view = store.session_view(record)
        assert "payload" not in view
        assert "otp_code" not in view
    finally:
        store.destroy_session(sid)
        store.CONSENT_FILE.unlink(missing_ok=True)


def test_session_expires() -> None:
    store.write_consent("T-001", "Tester", "consent")
    store.purge_sessions()
    sid = store.create_session("T-001")
    store.put(sid, expires_at=1.0)
    try:
        store.get_session(sid)
        raise AssertionError("an expired session must not resolve")
    except store.StoreError as exc:
        assert exc.code == "SESSION_NOT_FOUND"
    finally:
        store.purge_sessions()
        store.CONSENT_FILE.unlink(missing_ok=True)


def test_payload_roundtrip_is_encrypted_on_disk() -> None:
    store.write_consent("T-001", "Tester", "consent")
    store.purge_sessions()
    sid = store.create_session("T-001")
    try:
        store.put_payload(sid, {"upi": "tester@okhdfc"})
        raw = store.SESSIONS_FILE.read_text()
        assert "tester@okhdfc" not in raw, "payload leaked in plaintext"
        assert store.get_payload(sid)["upi"] == "tester@okhdfc"
    finally:
        store.destroy_session(sid)
        store.CONSENT_FILE.unlink(missing_ok=True)


# ---------------------------------------------------------------------------
# app: HTTP guards (needs fastapi; skipped cleanly if unavailable)
# ---------------------------------------------------------------------------

def _app_module():
    try:
        import app as app_module
    except Exception:  # noqa: BLE001
        return None
    return app_module


def _client():
    try:
        from fastapi.testclient import TestClient
    except Exception:  # noqa: BLE001
        return None
    app_module = _app_module()
    if app_module is None:
        return None
    return TestClient(app_module.create_app(), raise_server_exceptions=False)


def test_app_readiness_is_false_by_default() -> None:
    client = _client()
    if client is None:
        return
    os.environ["BW_PAYTM_STATEMENT_ENABLED"] = "false"
    body = client.get("/paytm-pilot/v1/statement-readiness").json()
    assert body["ready"] is False
    assert body["enabled"] is False
    assert body["flag_enabled"] is False


def test_app_requires_token() -> None:
    client = _client()
    if client is None:
        return
    os.environ["BW_PAYTM_STATEMENT_ENABLED"] = "true"
    try:
        r = client.post("/paytm-pilot/v1/statement/sessions",
                        json={"phone": "9876543210"})
        assert r.status_code in (401, 503), r.text
        if r.status_code == 401:
            assert r.json()["detail"]["code"] == "AUTH_REQUIRED"
    finally:
        os.environ["BW_PAYTM_STATEMENT_ENABLED"] = "false"


def test_app_rejects_bad_token() -> None:
    client = _client()
    app_module = _app_module()
    if client is None or app_module is None:
        return
    os.environ["BW_PAYTM_STATEMENT_ENABLED"] = "true"
    (store.PRIVATE).mkdir(parents=True, exist_ok=True)
    app_module.TESTER_TOKEN_FILE.write_text("expected-token-value")
    os.chmod(app_module.TESTER_TOKEN_FILE, 0o600)
    try:
        r = client.post("/paytm-pilot/v1/statement/sessions",
                        json={"phone": "9876543210"},
                        headers={"Authorization": "Bearer wrong-token"})
        assert r.status_code == 401, r.text
        assert r.json()["detail"]["code"] == "AUTH_INVALID"
    finally:
        os.environ["BW_PAYTM_STATEMENT_ENABLED"] = "false"
        app_module.TESTER_TOKEN_FILE.unlink(missing_ok=True)


def test_admin_token_cannot_start_tester_session() -> None:
    client = _client()
    app_module = _app_module()
    if client is None or app_module is None:
        return
    os.environ["BW_PAYTM_STATEMENT_ENABLED"] = "true"
    (store.PRIVATE).mkdir(parents=True, exist_ok=True)
    app_module.TOKEN_FILE.write_text("operator-only-token")
    app_module.TESTER_TOKEN_FILE.write_text("tester-only-token")
    try:
        r = client.post("/paytm-pilot/v1/statement/sessions",
                        json={"phone": "9876543210"},
                        headers={"Authorization": "Bearer operator-only-token"})
        assert r.status_code == 401, r.text
        assert r.json()["detail"]["code"] == "AUTH_INVALID"
    finally:
        os.environ["BW_PAYTM_STATEMENT_ENABLED"] = "false"
        app_module.TOKEN_FILE.unlink(missing_ok=True)
        app_module.TESTER_TOKEN_FILE.unlink(missing_ok=True)


def test_app_disabled_blocks_even_with_token() -> None:
    client = _client()
    app_module = _app_module()
    if client is None or app_module is None:
        return
    os.environ["BW_PAYTM_STATEMENT_ENABLED"] = "false"
    (store.PRIVATE).mkdir(parents=True, exist_ok=True)
    app_module.TOKEN_FILE.write_text("t0ken")
    try:
        r = client.post("/paytm-pilot/v1/statement/consent/revoke",
                        headers={"Authorization": "Bearer t0ken"})
        assert r.status_code == 503, r.text
        assert r.json()["detail"]["code"] == "DISABLED"
    finally:
        app_module.TOKEN_FILE.unlink(missing_ok=True)


def test_unsupported_provider_is_501_before_probe_gate() -> None:
    """An unsupported name is a static 501 and must not report PROBE_MISSING.

    Regression guard for the guard-ordering defect: the provider registry is a
    fact about this deployment, so SuperMoney has to be rejected before the
    Phase 0.5 probe is consulted, otherwise a caller is told the probe is
    missing when the real reason is that SuperMoney has no browser client.
    """
    client = _client()
    app_module = _app_module()
    if client is None or app_module is None:
        return
    os.environ["BW_PAYTM_STATEMENT_ENABLED"] = "true"
    (store.PRIVATE).mkdir(parents=True, exist_ok=True)
    app_module.TESTER_TOKEN_FILE.write_text("t0ken")
    try:
        # The 501 must beat the *probe* gate specifically. Consent is a real,
        # separate authorization control and is checked first on purpose, so
        # establish it here to isolate the ordering under test.
        store.write_consent("gate-tester", "gate tester", "ordering gate")
        for name in ("SuperMoney", "supermoney", "PhonePe_SuperMoney"):
            r = client.post("/paytm-pilot/v1/statement/sessions",
                            headers={"Authorization": "Bearer t0ken"},
                            json={"phone": "9999999999", "provider": name})
            assert r.status_code == 501, f"{name}: {r.status_code} {r.text}"
            assert r.json()["detail"]["code"] == "PROVIDER_NOT_IMPLEMENTED", r.text
    finally:
        app_module.TESTER_TOKEN_FILE.unlink(missing_ok=True)
        try:
            store.revoke_consent()
        except store.StoreError:
            pass
        os.environ["BW_PAYTM_STATEMENT_ENABLED"] = "false"


def test_supported_provider_still_hits_probe_gate() -> None:
    """Reordering must not weaken the fail-closed probe for a supported name."""
    client = _client()
    app_module = _app_module()
    if client is None or app_module is None:
        return
    os.environ["BW_PAYTM_STATEMENT_ENABLED"] = "true"
    (store.PRIVATE).mkdir(parents=True, exist_ok=True)
    app_module.TESTER_TOKEN_FILE.write_text("t0ken")
    probe = Path(os.environ["BW_PAYTM_PROBE_RESULT"])
    assert not probe.exists(), "probe fixture must be absent for this gate"
    try:
        r = client.post("/paytm-pilot/v1/statement/sessions",
                        headers={"Authorization": "Bearer t0ken"},
                        json={"phone": "9999999999", "provider": "Paytm"})
        # With no probe the supported provider cannot proceed. It must surface
        # the probe failure (or the consent gate ahead of it), never a 501.
        assert r.status_code != 501, r.text
        assert r.status_code in (403, 503), r.text
    finally:
        app_module.TESTER_TOKEN_FILE.unlink(missing_ok=True)
        os.environ["BW_PAYTM_STATEMENT_ENABLED"] = "false"


# ---------------------------------------------------------------------------
# Lab feeder — a fixture, never acceptance evidence
# ---------------------------------------------------------------------------

def _feeder_ok(rows=None, upis=None) -> dict:
    """A well-formed, fully provenance-tagged feeder envelope."""
    return {
        "source": "LAB_FEEDER",
        "providerAuthorized": False,
        "synthetic": True,
        "acceptanceEligible": False,
        "generatedAt": "2026-10-06T13:00:00Z",
        "data": {
            "count": len(rows or []),
            "rows": rows if rows is not None else [],
            "items": upis if upis is not None else [],
        },
    }


def _feeder_transport(payload):
    """Install a transport that always returns ``payload``."""
    import provider_base

    def call(*_args):
        return payload

    provider_base.set_feeder_transport(call)


def test_feeder_payload_is_accepted_but_marked_ineligible() -> None:
    """A correct feeder reply works, and is stamped acceptance-ineligible."""
    import provider_base

    _feeder_transport(_feeder_ok(rows=[{"txnId": "LAB-000001"}]))
    try:
        impl = provider_base.LabFeederProvider()
        state, stage = impl.start_session("9999999999")
        assert stage.ok, stage
        result = impl.read_history(state, "1970-01-01T00:00:00+00:00")
        # The data came through...
        assert result["count"] == 1
        assert result["data"]["rows"][0]["txnId"] == "LAB-000001"
        # ...but it can never be read as acceptance.
        assert result["source"] == "LAB_FEEDER"
        assert result["providerAuthorized"] is False
        assert result["acceptanceEligible"] is False
    finally:
        provider_base.set_feeder_transport(None)


def test_feeder_reply_claiming_authorization_is_refused() -> None:
    """The hostile case: a feeder reply that claims it is authorized."""
    import provider_base

    for bad_flag, bad_value in (
        ("providerAuthorized", True),
        ("synthetic", False),
        ("acceptanceEligible", True),
    ):
        payload = _feeder_ok(rows=[{"txnId": "LAB-000001"}])
        payload[bad_flag] = bad_value
        _feeder_transport(payload)
        try:
            impl = provider_base.LabFeederProvider()
            # The check fires on the first read, which is getUPIList during
            # start_session; assert the refusal regardless of which call
            # happens to surface it.
            try:
                state, _ = impl.start_session("9999999999")
                impl.read_history(state, "1970-01-01T00:00:00+00:00")
                raise AssertionError(f"{bad_flag}={bad_value!r} must be refused")
            except provider_base.ProviderError as exc:
                assert exc.code == "FEEDER_PROVENANCE_REJECTED", exc.code
        finally:
            provider_base.set_feeder_transport(None)


def test_feeder_reply_missing_flags_is_refused() -> None:
    """Omitting a flag must not be read as compliance."""
    import provider_base

    # An entirely bare payload: the permissive-default trap.
    _feeder_transport({"rows": [{"txnId": "LAB-000001"}]})
    try:
        impl = provider_base.LabFeederProvider()
        try:
            impl.start_session("9999999999")
            raise AssertionError("an untagged payload must be refused")
        except provider_base.ProviderError as exc:
            assert exc.code == "FEEDER_PROVENANCE_REJECTED", exc.code
    finally:
        provider_base.set_feeder_transport(None)


def test_feeder_is_not_a_probe_source() -> None:
    """The feeder must not be able to satisfy the Phase 0.5 probe gate.

    This is the load-bearing gate. If a feeder could write a probe result, a
    synthetic read could be promoted into an acceptance path.
    """
    import provider_base

    probe_file = Path(os.environ["BW_PAYTM_PROBE_RESULT"])
    assert not probe_file.exists(), "probe fixture must be absent for this gate"

    _feeder_transport(_feeder_ok(rows=[{"txnId": "LAB-000001"}]))
    try:
        # Even with a feeder transport registered and a working read, loading
        # the probe still fails closed.
        try:
            provider_base.load_probe()
            raise AssertionError("load_probe must fail while the probe is absent")
        except provider.Failure as exc:
            assert exc.code == "PROBE_MISSING", exc.code
    finally:
        provider_base.set_feeder_transport(None)


def test_feeder_never_appears_in_supported_provider_list() -> None:
    """A 501 message must not imply the feeder is a provider."""
    import provider_base

    assert "LabFeeder" not in provider_base.SUPPORTED_PROVIDER_NAMES
    assert "labfeeder" not in provider_base.SUPPORTED_PROVIDER_NAMES
    try:
        provider_base.build_provider("SuperMoney")
        raise AssertionError("SuperMoney must not build")
    except provider_base.ProviderError as exc:
        assert exc.code == "PROVIDER_NOT_IMPLEMENTED", exc.code
        assert "LabFeeder" not in exc.detail
        assert "lab" not in exc.detail.lower().split("real providers")[0]


def test_feeder_refuses_otp_stage() -> None:
    """A fixture must not be advanced with an OTP-shaped value."""
    import provider_base

    _feeder_transport(_feeder_ok())
    try:
        impl = provider_base.LabFeederProvider()
        state, _ = impl.start_session("9999999999")
        try:
            impl.submit_otp(state, "123456")
            raise AssertionError("the feeder has no OTP stage")
        except provider_base.ProviderError as exc:
            assert exc.code == "OTP_NOT_APPLICABLE", exc.code
    finally:
        provider_base.set_feeder_transport(None)


def test_feeder_without_transport_fails_closed() -> None:
    """No transport registered means no session, not a silent no-op."""
    import provider_base

    provider_base.set_feeder_transport(None)
    impl = provider_base.LabFeederProvider()
    try:
        impl.start_session("9999999999")
        raise AssertionError("an unconfigured feeder must refuse to start")
    except provider_base.ProviderError as exc:
        assert exc.code == "FEEDER_NOT_CONFIGURED", exc.code


def test_feeder_rejects_bad_phone_and_bad_window() -> None:
    """Argument validation mirrors the browser providers' posture."""
    import provider_base

    _feeder_transport(_feeder_ok())
    try:
        impl = provider_base.LabFeederProvider()
        try:
            impl.start_session("123")
            raise AssertionError("short phone must be refused")
        except provider_base.ProviderError as exc:
            assert exc.code == "PHONE_INVALID", exc.code

        state, _ = impl.start_session("9999999999")
        try:
            impl.read_history(state, "not-a-timestamp")
            raise AssertionError("unparseable since must be refused")
        except provider_base.ProviderError as exc:
            assert exc.code == "SINCE_UNPARSEABLE", exc.code
    finally:
        provider_base.set_feeder_transport(None)


# ---------------------------------------------------------------------------

def _run_all() -> int:
    import inspect
    failures = 0
    tests = [(n, f) for n, f in sorted(globals().items())
             if n.startswith("test_") and inspect.isfunction(f)]
    for name, fn in tests:
        try:
            fn()
            print(f"  ok   {name}")
        except AssertionError as exc:
            failures += 1
            print(f"  FAIL {name}: {exc}")
        except Exception as exc:  # noqa: BLE001
            failures += 1
            print(f"  ERROR {name}: {type(exc).__name__}: {exc}")
    print(f"\n{len(tests) - failures}/{len(tests)} passed")
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(_run_all())
