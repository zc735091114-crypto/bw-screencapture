"""BW UPI statement pilot — HTTP surface.

Fail-closed by design. Every route requires, in order:

1. ``BW_PAYTM_STATEMENT_ENABLED=true``   (operator kill-switch, default off)
2. a bearer token matching ``private/api.token``
3. a live consent record from the tester
4. the Phase 0.5 probe result, before any browser launches

The pre-existing pilot router is *imported*, never edited — the host app at
``/home/ec2-user/bw-paytm-pilot`` is left byte-identical.
"""

from __future__ import annotations

import json
import os
import secrets
import sys
import time
from pathlib import Path
from typing import Any, Optional

_SRC_DIR = str(Path(__file__).resolve().parent)
if _SRC_DIR not in sys.path:
    sys.path.insert(0, _SRC_DIR)

from fastapi import Depends, FastAPI, Header, HTTPException, Query, Request
from fastapi.responses import JSONResponse

import provider
import provider_base
import store
from store import StoreError

BASE = Path(os.environ.get("BW_PAYTM_STATEMENT_DIR",
                            "/home/ec2-user/bw-paytm-statement"))
PRIVATE = BASE / "private"
TOKEN_FILE = PRIVATE / "api.token"
TESTER_TOKEN_FILE = PRIVATE / "tester.api.token"
PILOT_APP_DIR = Path(os.environ.get(
    "BW_PILOT_APP_DIR", "/home/ec2-user/bw-paytm-pilot"))

# Live sessions. In-process on purpose: no cross-process handle is
# serialisable, and a restart invalidating the session is the safe failure.
_SESSIONS: dict[str, dict[str, Any]] = {}


SUPPORTED_PROVIDERS = ("Paytm", "PhonePe", "MobiKwik", "Navi", "Amazon")
SUPPORTED_PROVIDER = "Paytm"

# ---------------------------------------------------------------------------
# Shared guards
# ---------------------------------------------------------------------------

def _enabled() -> None:
    if os.environ.get("BW_PAYTM_STATEMENT_ENABLED", "false").lower() != "true":
        raise HTTPException(503, detail={"code": "DISABLED",
                                         "detail": "feature flag is off"})


def _require_token(authorization: str | None) -> None:
    _enabled()
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(401, detail={"code": "AUTH_REQUIRED"})
    supplied = authorization.removeprefix("Bearer ").strip()
    if not TOKEN_FILE.exists():
        raise HTTPException(503, detail={"code": "AUTH_NOT_PROVISIONED"})
    expected = TOKEN_FILE.read_text(encoding="utf-8").strip()
    if not expected or not secrets.compare_digest(supplied, expected):
        raise HTTPException(401, detail={"code": "AUTH_INVALID"})


def _require_tester_token(authorization: str | None) -> None:
    """Grant only session actions; the operator token remains admin-only."""
    _enabled()
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(401, detail={"code": "AUTH_REQUIRED"})
    if not TESTER_TOKEN_FILE.exists():
        raise HTTPException(503, detail={"code": "TESTER_ACCESS_NOT_PROVISIONED"})
    supplied = authorization.removeprefix("Bearer ").strip()
    expected = TESTER_TOKEN_FILE.read_text(encoding="utf-8").strip()
    if not expected or not secrets.compare_digest(supplied, expected):
        raise HTTPException(401, detail={"code": "AUTH_INVALID"})


def _consent() -> dict[str, Any]:
    try:
        return store.require_consent()
    except StoreError as exc:
        raise HTTPException(exc.http_status,
                            detail={"code": exc.code, "detail": exc.detail})


def _known_provider(provider_name: str) -> str:
    """Reject an unsupported provider before any probe or browser work.

    The registry is a static fact about this deployment, so an unsupported name
    is answerable without touching the Phase 0.5 probe. Checking it ahead of
    ``_probe()`` keeps the 501 honest: SuperMoney has no browser client, and
    reporting PROBE_MISSING instead would misattribute the real reason.
    """
    if provider_base.get_provider(provider_name) is None:
        raise HTTPException(501, detail={
            "code": "PROVIDER_NOT_IMPLEMENTED",
            "detail": f"{provider_name} has no browser client; "
                      f"supported providers: {', '.join(SUPPORTED_PROVIDERS)}",
        })
    return provider_name


def _probe() -> dict[str, Any]:
    try:
        return provider.load_probe()
    except provider.Failure as exc:
        raise HTTPException(exc.http_status,
                            detail={"code": exc.code, "detail": exc.detail})


def _session(session_id: str) -> dict[str, Any]:
    try:
        return store.get_session(session_id)
    except StoreError as exc:
        raise HTTPException(exc.http_status,
                            detail={"code": exc.code, "detail": exc.detail})


def _live_session(session_id: str) -> dict[str, Any]:
    state = _SESSIONS.get(session_id)
    if not state:
        raise HTTPException(410, detail={
            "code": "SESSION_ORPHANED",
            "detail": "session gone (restart or expiry) — start a new session",
        })
    return state


def _guard(exc: provider.Failure) -> HTTPException:
    detail: dict[str, Any] = {"code": exc.code}
    if exc.detail:
        detail["detail"] = exc.detail
    if exc.retry_after:
        detail["retry_after"] = exc.retry_after
    return HTTPException(exc.http_status, detail=detail)


# ---------------------------------------------------------------------------
# App
# ---------------------------------------------------------------------------

def create_app() -> FastAPI:
    app = FastAPI(
        title="BW UPI Statement Pilot",
        version="0.1.0",
        docs_url=None,
        redoc_url=None,
        openapi_url=None,
    )

    # Pre-existing pilot routes — imported read-only, never modified.
    try:
        sys.path.insert(0, str(PILOT_APP_DIR))
        from paytm_screenshot_pilot.api import router as pilot_router
        app.include_router(pilot_router)
        pilot_loaded = True
    except Exception:  # noqa: BLE001 — statement routes stand alone
        pilot_loaded = False

    auth = Header(default=None)

    # -- readiness ---------------------------------------------------------
    @app.get("/paytm-pilot/v1/statement-readiness")
    def statement_readiness() -> JSONResponse:
        flag = os.environ.get("BW_PAYTM_STATEMENT_ENABLED", "false").lower() == "true"
        consent_ok = False
        try:
            store.require_consent()
            consent_ok = True
        except StoreError:
            consent_ok = False
        probe_ok = provider.PROBE_RESULT.exists()
        body = {
            "flag_enabled": flag,
            "consent_present": consent_ok,
            "probe_present": bool(probe_ok),
            "auth_provisioned": TESTER_TOKEN_FILE.exists(),
            "pilot_router_loaded": pilot_loaded,
            "ready": bool(flag and consent_ok and probe_ok and TESTER_TOKEN_FILE.exists()),
            "enabled": False,
            "mode": "isolated",
        }
        return JSONResponse(body)

    # -- consent -----------------------------------------------------------
    @app.post("/paytm-pilot/v1/statement/consent")
    async def statement_consent(request: Request,
                                authorization: str | None = auth) -> dict[str, Any]:
        _require_token(authorization)
        body = await _json_body(request)
        return store.write_consent(
            str(body.get("tester_id", "")).strip(),
            str(body.get("tester_name", "")).strip(),
            str(body.get("statement", "")).strip(),
        )

    @app.post("/paytm-pilot/v1/statement/consent/revoke")
    def statement_consent_revoke(authorization: str | None = auth) -> dict[str, Any]:
        _require_token(authorization)
        record = store.revoke_consent()
        for sid in list(_SESSIONS):
            provider.close(_SESSIONS.pop(sid, {}))
        return {"revoked": True, "tester_id": record.get("tester_id"),
                "purged_sessions": True}

    # -- sessions ----------------------------------------------------------
    @app.post("/paytm-pilot/v1/statement/sessions")
    async def statement_session_create(request: Request,
                                       authorization: str | None = auth) -> dict[str, Any]:
        _require_tester_token(authorization)
        _consent()
        body = await _json_body(request)

        # The live client is browser-based and fail-closed. Resolve the provider
        # name first: an unsupported name is a static 501 and must not be
        # reported as a missing probe.
        provider_name = str(body.get("provider", "Paytm")).strip() or "Paytm"
        _known_provider(provider_name)

        _probe()
        phone = str(body.get("phone", "")).strip()
        if not phone.isdigit() or not (10 <= len(phone) <= 13):
            raise HTTPException(400, detail={"code": "PHONE_INVALID"})

        session_id = store.create_session(str(_consent().get("tester_id", "")))
        try:
            state, stage = provider_base.start(phone, provider_name=provider_name)
        except provider.Failure as exc:
            store.destroy_session(session_id)
            raise _guard(exc) from exc
        except Exception as exc:  # noqa: BLE001
            store.destroy_session(session_id)
            raise HTTPException(502, detail={
                "code": "BROWSER_ERROR",
                "detail": f"{type(exc).__name__}: {str(exc)[:300]}"}) from exc

        _SESSIONS[session_id] = state
        store.set_stage(session_id, stage.name)
        return {"session_id": session_id, "stage": stage.name,
                "matched_by": stage.matched_by,
                "provider": provider_name,
                "next": "POST /statement/sessions/{id}/otp"}

    @app.post("/paytm-pilot/v1/statement/sessions/{session_id}/otp")
    async def statement_otp(session_id: str, request: Request,
                            authorization: str | None = auth) -> dict[str, Any]:
        _require_tester_token(authorization)
        _consent()
        _probe()
        record = _session(session_id)
        state = _live_session(session_id)

        body = await _json_body(request)
        otp = str(body.get("otp", ""))
        if not otp.strip().isdigit():
            raise HTTPException(400, detail={"code": "OTP_MALFORMED"})
        if record.get("otp_presented"):
            raise HTTPException(409, detail={
                "code": "OTP_ALREADY_USED",
                "detail": "one code per session; create a new session"})

        try:
            stage = provider.submit_otp(state, otp)
        except provider.Failure as exc:
            if exc.code in {"MANUAL_VERIFICATION_REQUIRED", "OTP_LIMITED"}:
                _teardown(session_id)
            raise _guard(exc) from exc
        finally:
            # The code never survives this request.
            body.pop("otp", None)
            otp = ""

        store.put(session_id, stage=stage.name, otp_presented=True)
        return {"session_id": session_id, "stage": stage.name,
                "matched_by": stage.matched_by, "readonly": True}

    @app.get("/paytm-pilot/v1/statement/sessions/{session_id}")
    def statement_session_get(session_id: str,
                              authorization: str | None = auth) -> dict[str, Any]:
        _require_tester_token(authorization)
        _consent()
        view = store.session_view(_session(session_id))
        view["browser_alive"] = session_id in _SESSIONS
        return view

    @app.get("/paytm-pilot/v1/statement/sessions/{session_id}/transactions")
    def statement_transactions(
        session_id: str,
        since: str = Query(default="1970-01-01T00:00:00+05:30"),
        authorization: str | None = auth,
    ) -> dict[str, Any]:
        _require_tester_token(authorization)
        _consent()
        _probe()
        record = _session(session_id)
        if not record.get("otp_presented"):
            raise HTTPException(409, detail={"code": "NOT_AUTHENTICATED",
                                             "detail": "submit OTP first"})
        state = _live_session(session_id)
        try:
            result = provider.read_history(state, since)
        except provider.Failure as exc:
            if exc.code in {"MANUAL_VERIFICATION_REQUIRED", "UNEXPECTED_DESTINATION"}:
                _teardown(session_id)
            raise _guard(exc) from exc
        except Exception as exc:  # noqa: BLE001
            raise HTTPException(502, detail={
                "code": "BROWSER_ERROR",
                "detail": f"{type(exc).__name__}: {str(exc)[:300]}"}) from exc

        store.set_stage(session_id, "history_read")
        return {"session_id": session_id, "since": since,
                "count": result["count"], "rows": result["rows"],
                "source": result["source"], "readonly": True}

    @app.delete("/paytm-pilot/v1/statement/sessions/{session_id}")
    def statement_session_delete(session_id: str,
                                 authorization: str | None = auth) -> dict[str, Any]:
        _require_tester_token(authorization)
        _consent()
        _session(session_id)
        _teardown(session_id)
        return {"deleted": True, "session_id": session_id}

    # -- teardown on shutdown ---------------------------------------------
    @app.on_event("shutdown")
    def _shutdown() -> None:
        for sid in list(_SESSIONS):
            _teardown(sid)

    return app


def _teardown(session_id: str) -> None:
    provider.close(_SESSIONS.pop(session_id, {}))
    try:
        store.destroy_session(session_id)
    except StoreError:
        pass


async def _json_body(request: Request) -> dict[str, Any]:
    """Read and decode the request body; a malformed body is an empty dict."""
    try:
        raw = await request.body()
        data = json.loads(raw.decode("utf-8")) if raw else {}
        return data if isinstance(data, dict) else {}
    except Exception:  # noqa: BLE001
        return {}


def production() -> FastAPI:
    return create_app()


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(create_app(),
                host=os.environ.get("BW_BIND_HOST", "127.0.0.1"),
                port=int(os.environ.get("BW_BIND_PORT", "18450")),
                log_level="info")