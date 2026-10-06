"""Paytm consumer-web statement client.

Read-only, consent-gated, single session. Modelled on
/home/ec2-user/bw-amazon-feasibility/provider.py (classify/diagnose shape).

FAILOWS CLOSED: this module refuses to launch a browser until Phase 0.5 has
written a confirmed probe result. Until then every entry point raises
``Failure("PROBE_MISSING"|"PROBE_NOT_CONFIRMED"|"NOT_CONFIGURED")``.

Never: UPI PIN, payment screen, OTP storage, SMS reading, batch session reuse.
"""

from __future__ import annotations

import json
import os
import re
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Callable
from urllib.parse import urlparse

# ---------------------------------------------------------------------------
# Configuration (all supplied by the Phase 0.5 probe or the operator)
# ---------------------------------------------------------------------------

PROBE_RESULT = Path(
    os.environ.get(
        "BW_PAYTM_PROBE_RESULT",
        "/home/ec2-user/bw-paytm-statement/private/PROBE-RESULT.json",
    )
)

# Host allowlist. Empty until probe confirms — nothing is reachable then.
_ALLOWED_HOSTS: frozenset[str] = frozenset()

# Hard stop rules, inherited from the Amazon provider contract.
OTP_LIMITED_SECONDS = 86400
MANUAL_VERIFICATION_REQUIRED = "MANUAL_VERIFICATION_REQUIRED"
READ_ONLY = "READ_ONLY"


class Failure(Exception):
    """Recoverable/diagnosable provider failure."""

    def __init__(self, code: str, http_status: int = 502, retry_after: int = 0,
                 detail: str = "") -> None:
        super().__init__(code)
        self.code = code
        self.http_status = http_status
        self.retry_after = retry_after
        self.detail = detail


@dataclass
class Stage:
    """Where the browser is in the login->history flow."""

    name: str
    ok: bool
    matched_by: str = ""
    detail: str = ""
    diagnostics: dict[str, Any] = field(default_factory=dict)


# ---------------------------------------------------------------------------
# Shape classification (mirrors the Amazon provider's generic probes)
# ---------------------------------------------------------------------------

_BLOCKING_SHAPES: list[tuple[str, re.Pattern[str]]] = [
    ("OTP_LIMITED", re.compile(r"too many (attempts|otp)|try again (in|after)|limit exceeded", re.I)),
    ("MANUAL_VERIFICATION_REQUIRED", re.compile(r"call (our )?support|contact support|visit (our )?(store|branch)|manual verification|needs (human|manual)", re.I)),
    ("UNEXPECTED_DESTINATION", re.compile(r"sign in with (google|apple|email)|welcome back", re.I)),
    ("CORRECT_OTP", re.compile(r"verification successful|verified successfully|authenticat(ed|ion) success", re.I)),
]


def classify(text: str, path: str = "") -> Stage:
    """Return the first matching blocking/terminal shape for rendered text.

    Deliberately shape-based (never banner wording) so a cosmetic copy change
    cannot silently flip a session into success.
    """
    blob = text or ""
    for name, pattern in _BLOCKING_SHAPES:
        if pattern.search(blob):
            return Stage(name=name, ok=False, matched_by=f"shape:{name}",
                         detail=blob[:240])
    if not blob.strip():
        return Stage("EMPTY", False, "shape:EMPTY", "no rendered text")
    return Stage("UNKNOWN", False, "shape:UNKNOWN", blob[:240])


def _host_allowed(url: str) -> bool:
    if not _ALLOWED_HOSTS:
        return False
    host = (urlparse(url).hostname or "").lower()
    return host in _ALLOWED_HOSTS


# ---------------------------------------------------------------------------
# Probe gate — the Phase 0.5 output that unlocks everything else
# ---------------------------------------------------------------------------

def load_probe() -> dict[str, Any]:
    """Load and validate the Phase 0.5 probe result. Raises Failure if unusable."""
    if not PROBE_RESULT.exists():
        raise Failure("PROBE_MISSING", http_status=503,
                      detail=f"{PROBE_RESULT} absent — Phase 0.5 not run")
    try:
        data = json.loads(PROBE_RESULT.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise Failure("PROBE_UNREADABLE", http_status=503, detail=str(exc)) from exc

    if data.get("surface") != "WEB_LOGIN_CONFIRMED":
        raise Failure(
            "PROBE_NOT_CONFIRMED", http_status=503,
            detail="Phase 0.5 has not confirmed a web balance/history surface",
        )

    login_url = str(data.get("login_url") or "")
    history_url = str(data.get("history_url") or "")
    host = str(data.get("host") or "").lower()
    if not (login_url and history_url and host):
        raise Failure("PROBE_INCOMPLETE", http_status=503,
                      detail="probe must supply host, login_url, history_url")

    global _ALLOWED_HOSTS
    _ALLOWED_HOSTS = frozenset({host})
    return data


def require_flag() -> None:
    """Honour the explicit operator kill-switch (default: disabled)."""
    if os.environ.get("BW_PAYTM_STATEMENT_ENABLED", "false").lower() != "true":
        raise Failure("DISABLED", http_status=503,
                      detail="BW_PAYTM_STATEMENT_ENABLED is not true")


# ---------------------------------------------------------------------------
# Browser session
# ---------------------------------------------------------------------------

_SELECTORS = {
    "phone_input": 'input[name="phone"], input[type="tel"], input[autocomplete="tel"]',
    "otp_input": 'input[name="otp"], input[inputmode="numeric"], input[autocomplete="one-time-code"]',
    "submit": 'button[type="submit"]',
}


def _pw():
    from playwright.sync_api import sync_playwright  # local import: keeps unit tests light
    return sync_playwright()


def _launch(pw, probe: dict[str, Any]):
    browser = pw.chromium.launch(
        headless=True,
        args=["--no-sandbox", "--disable-dev-shm-usage", "--disable-gpu"],
    )
    ctx = browser.new_context(
        user_agent=probe.get("user_agent") or None,
        locale="en-IN",
        viewport={"width": 1280, "height": 900},
    )
    ctx.set_default_timeout(20_000)
    page = ctx.new_page()
    return browser, ctx, page


def _guard_destination(page) -> None:
    url = page.url or ""
    if not _host_allowed(url):
        raise Failure("UNEXPECTED_DESTINATION", http_status=502,
                      detail=f"browser left allowlist: {url[:160]}")


def start(phone: str) -> tuple[Any, dict[str, Any]]:
    """Open the login surface and reach the OTP challenge.

    Returns (session_state, Stage). The phone number is the tester's own; no
    request is sent to Paytm's APIs, only a browser navigation.
    """
    require_flag()
    probe = load_probe()
    pw = _pw().start()
    browser, ctx, page = _launch(pw, probe)
    state: dict[str, Any] = {"pw": pw, "browser": browser, "ctx": ctx,
                             "page": page, "phone": phone, "stage": "init"}
    try:
        page.goto(probe["login_url"], wait_until="domcontentloaded")
        _guard_destination(page)
        page.wait_for_timeout(1_500)
        if not page.locator(_SELECTORS["phone_input"]).first.is_visible():
            stage = classify(page.inner_text("body")[:2000], page.url)
            close(state)
            raise Failure("LOGIN_SURFACE_MISMATCH", http_status=502,
                          detail=f"phone input absent; shape={stage.name}")
        page.fill(_SELECTORS["phone_input"], phone)
        page.click(_SELECTORS["submit"])
        page.wait_for_timeout(2_500)
        _guard_destination(page)
        if not page.locator(_SELECTORS["otp_input"]).first.is_visible():
            stage = classify(page.inner_text("body")[:2000], page.url)
            close(state)
            if stage.name == "MANUAL_VERIFICATION_REQUIRED":
                raise Failure(MANUAL_VERIFICATION_REQUIRED, http_status=503,
                              detail=stage.detail)
            raise Failure("OTP_CHALLENGE_NOT_REACHED", http_status=502,
                          detail=f"shape={stage.name}: {stage.detail}")
        state["stage"] = "otp_required"
        return state, Stage("otp_required", True, "visible:otp_input")
    except Failure:
        close(state)
        raise
    except Exception as exc:  # noqa: BLE001 — surface as a diagnosable failure
        close(state)
        raise Failure("BROWSER_ERROR", http_status=502,
                      detail=f"{type(exc).__name__}: {str(exc)[:300]}") from exc


def submit_otp(state: dict[str, Any], otp: str) -> Stage:
    """Submit the operator-supplied one-time code.

    The value is never written to state, logs, or disk; it dies with this call.
    """
    if not otp or not otp.strip().isdigit() or len(otp.strip()) < 4:
        raise Failure("OTP_INVALID", http_status=400, detail="expected 4-8 digits")
    page = state["page"]
    try:
        page.fill(_SELECTORS["otp_input"], otp.strip())
        del otp
        page.click(_SELECTORS["submit"])
        page.wait_for_timeout(4_000)
        _guard_destination(page)
        body = page.inner_text("body")[:4000]
    except Exception as exc:  # noqa: BLE001
        raise Failure("BROWSER_ERROR", http_status=502,
                      detail=f"{type(exc).__name__}: {str(exc)[:300]}") from exc
    finally:
        state.pop("_otp", None)

    stage = classify(body, page.url)
    if stage.name == "OTP_LIMITED":
        raise Failure("OTP_LIMITED", http_status=429,
                      retry_after=OTP_LIMITED_SECONDS, detail=stage.detail)
    if stage.name == "MANUAL_VERIFICATION_REQUIRED":
        raise Failure(MANUAL_VERIFICATION_REQUIRED, http_status=503,
                      detail=stage.detail)
    if stage.name == "CORRECT_OTP":
        state["stage"] = "authenticated"
        return Stage("authenticated", True, stage.matched_by, stage.detail)
    raise Failure("OTP_REJECTED", http_status=502, detail=stage.detail)


def _row_text(page) -> list[dict[str, str]]:
    """Best-effort transaction rows from the history surface."""
    rows: list[dict[str, str]] = []
    for selector in ('table tbody tr', '[role="list"] li', 'li, div[class*="row"]'):
        try:
            locators = page.locator(selector)
            count = min(locators.count(), 400)
            for i in range(count):
                text = (locators.nth(i).inner_text() or "").strip()
                if text:
                    rows.append({"text": text})
            if rows:
                break
        except Exception:  # noqa: BLE001 — selector miss is not an error
            continue
    return rows


def read_history(state: dict[str, Any], since_iso: str) -> dict[str, Any]:
    """Navigate to history and return rows; strictly read-only."""
    require_flag()
    probe = load_probe()
    page = state["page"]
    try:
        page.goto(probe["history_url"], wait_until="domcontentloaded")
        _guard_destination(page)
        page.wait_for_timeout(3_000)
        rows = _row_text(page)
    except Failure:
        raise
    except Exception as exc:  # noqa: BLE001
        raise Failure("BROWSER_ERROR", http_status=502,
                      detail=f"{type(exc).__name__}: {str(exc)[:300]}") from exc

    if not rows:
        body = page.inner_text("body")[:3000]
        stage = classify(body, page.url)
        if stage.name in {"MANUAL_VERIFICATION_REQUIRED", "UNEXPECTED_DESTINATION"}:
            raise Failure(stage.name, http_status=503, detail=stage.detail)
        raise Failure("HISTORY_EMPTY", http_status=502, detail=stage.detail)

    return {"rows": rows, "since": since_iso, "count": len(rows),
            "source": "consumer-web", "readonly": True}


def close(state: dict[str, Any]) -> None:
    """Tear down the browser and drop every reference to the session."""
    if not state:
        return
    for key in ("page", "ctx", "browser"):
        obj = state.get(key)
        if obj is not None:
            try:
                obj.close()
            except Exception:  # noqa: BLE001
                pass
    pw = state.get("pw")
    if pw is not None:
        try:
            pw.stop()
        except Exception:  # noqa: BLE001
            pass
    state.clear()
