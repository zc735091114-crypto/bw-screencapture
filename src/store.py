"""Consent record and encrypted session store.

Two stores, one directory:

* ``private/consent.json``  — the written, revocable record that unlocks the
  whole feature. Absent == disabled (fail closed).
* ``private/sessions.json`` — AES-128-CBC via ``cryptography.fernet`` keyed by
  ``private/store.key`` (0600). Entries expire after ``SESSION_TTL_SECONDS``.

Nothing sensitive is ever persisted in plaintext: OTPs are never written at
all, and phone numbers / upi ids live only inside the Fernet envelope.
"""

from __future__ import annotations

import json
import os
import secrets
import time
import uuid
from pathlib import Path
from typing import Any

BASE = Path(os.environ.get("BW_PAYTM_STATEMENT_DIR",
                           "/home/ec2-user/bw-paytm-statement"))
PRIVATE = BASE / "private"
CONSENT_FILE = PRIVATE / "consent.json"
KEY_FILE = PRIVATE / "store.key"
SESSIONS_FILE = PRIVATE / "sessions.json"

SESSION_TTL_SECONDS = int(os.environ.get("BW_PAYTM_SESSION_TTL", "900"))
CONSENT_TTL_SECONDS = int(os.environ.get("BW_PAYTM_CONSENT_TTL", "86400"))
MAX_SESSIONS = 1  # one tester, one live session, never a batch


class StoreError(Exception):
    def __init__(self, code: str, http_status: int = 400, detail: str = "") -> None:
        super().__init__(code)
        self.code = code
        self.http_status = http_status
        self.detail = detail


def _ensure_private() -> None:
    PRIVATE.mkdir(parents=True, exist_ok=True)
    os.chmod(PRIVATE, 0o700)


# ---------------------------------------------------------------------------
# Fernet key handling
# ---------------------------------------------------------------------------

def _fernet():
    from cryptography.fernet import Fernet
    _ensure_private()
    if not KEY_FILE.exists():
        KEY_FILE.write_bytes(Fernet.generate_key())
        os.chmod(KEY_FILE, 0o600)
    return Fernet(KEY_FILE.read_bytes())


def _enc(payload: dict[str, Any]) -> str:
    return _fernet().encrypt(json.dumps(payload, ensure_ascii=False).encode()).decode()


def _dec(token: str) -> dict[str, Any]:
    from cryptography.fernet import InvalidToken
    try:
        return json.loads(_fernet().decrypt(token.encode()))
    except InvalidToken as exc:
        raise StoreError("STORE_DECRYPT_FAILED", 500, "session key mismatch") from exc


# ---------------------------------------------------------------------------
# Consent
# ---------------------------------------------------------------------------

def write_consent(tester_id: str, tester_name: str, statement: str) -> dict[str, Any]:
    """Persist the written consent record (supplied by the operator, not typed here)."""
    if not tester_id or not statement.strip():
        raise StoreError("CONSENT_INCOMPLETE", 400, "tester_id and statement are required")
    _ensure_private()
    record = {
        "tester_id": tester_id,
        "tester_name": tester_name,
        "statement": statement.strip(),
        "written_at": time.time(),
        "expires_at": time.time() + CONSENT_TTL_SECONDS,
        "revoked": False,
        "scope": ["balance", "history"],
        "declared": [
            "read-only", "single account", "single tester",
            "no UPI PIN", "no payment", "no OTP retention",
        ],
    }
    CONSENT_FILE.write_text(json.dumps(record, indent=2, ensure_ascii=False))
    os.chmod(CONSENT_FILE, 0o600)
    return record


def require_consent() -> dict[str, Any]:
    """Return a live consent record or raise. Revoked/expired == disabled."""
    if not CONSENT_FILE.exists():
        raise StoreError("CONSENT_ABSENT", 403, "no consent record — feature disabled")
    try:
        record = json.loads(CONSENT_FILE.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise StoreError("CONSENT_UNREADABLE", 403, str(exc)) from exc
    if record.get("revoked"):
        raise StoreError("CONSENT_REVOKED", 403, "consent revoked by operator")
    if float(record.get("expires_at", 0)) < time.time():
        raise StoreError("CONSENT_EXPIRED", 403, "consent expired — renew with tester")
    return record


def revoke_consent() -> dict[str, Any]:
    """Irreversibly drop the consent record and every live session."""
    record = require_consent()
    record["revoked"] = True
    record["revoked_at"] = time.time()
    CONSENT_FILE.write_text(json.dumps(record, indent=2, ensure_ascii=False))
    os.chmod(CONSENT_FILE, 0o600)
    purge_sessions()
    return record


# ---------------------------------------------------------------------------
# Sessions
# ---------------------------------------------------------------------------

def _read_all() -> dict[str, Any]:
    if not SESSIONS_FILE.exists():
        return {}
    try:
        data = json.loads(SESSIONS_FILE.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return {}
    now = time.time()
    return {k: v for k, v in data.items()
            if isinstance(v, dict) and float(v.get("expires_at", 0)) > now}


def _write_all(data: dict[str, Any]) -> None:
    _ensure_private()
    tmp = SESSIONS_FILE.with_suffix(".tmp")
    tmp.write_text(json.dumps(data))
    os.chmod(tmp, 0o600)
    tmp.replace(SESSIONS_FILE)


def create_session(tester_id: str) -> str:
    """Allocate the single allowed session and return its opaque id."""
    require_consent()
    live = _read_all()
    if len(live) >= MAX_SESSIONS:
        raise StoreError("SESSION_LIMIT", 409,
                         f"at most {MAX_SESSIONS} live session(s) allowed")
    sid = secrets.token_urlsafe(24)
    live[sid] = {
        "session_id": sid,
        "tester_id": tester_id,
        "created_at": time.time(),
        "expires_at": time.time() + SESSION_TTL_SECONDS,
        "stage": "pending",
        "otp_presented": False,   # boolean only — the code itself is never stored
        "payload": "",            # Fernet blob for browser state
    }
    _write_all(live)
    return sid


def get_session(session_id: str) -> dict[str, Any]:
    live = _read_all()
    if session_id not in live:
        raise StoreError("SESSION_NOT_FOUND", 404, "unknown or expired session")
    return live[session_id]


def put(session_id: str, **fields: Any) -> dict[str, Any]:
    live = _read_all()
    if session_id not in live:
        raise StoreError("SESSION_NOT_FOUND", 404, "unknown or expired session")
    live[session_id].update(fields)
    _write_all(live)
    return live[session_id]


def set_stage(session_id: str, stage: str) -> None:
    put(session_id, stage=stage)


def put_payload(session_id: str, payload: dict[str, Any]) -> None:
    put(session_id, payload=_enc(payload))


def get_payload(session_id: str) -> dict[str, Any]:
    record = get_session(session_id)
    if not record.get("payload"):
        return {}
    return _dec(record["payload"])


def destroy_session(session_id: str) -> None:
    live = _read_all()
    if session_id in live:
        live[session_id].pop("payload", None)
        live.pop(session_id, None)
        _write_all(live)


def purge_sessions() -> int:
    live = _read_all()
    count = len(live)
    _write_all({})
    return count


def session_view(record: dict[str, Any]) -> dict[str, Any]:
    """Public projection — never includes payload or any secret."""
    return {
        "session_id": record.get("session_id"),
        "tester_id": record.get("tester_id"),
        "stage": record.get("stage"),
        "created_at": record.get("created_at"),
        "expires_at": record.get("expires_at"),
        "otp_presented": record.get("otp_presented", False),
        "remaining_seconds": max(
            0, int(float(record.get("expires_at", 0)) - time.time())),
    }
