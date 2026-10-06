"""General UPI statement framework with fail-closed provider routing.

This module intentionally reuses the working Paytm browser client in
``provider.py`` and adds a provider abstraction for PhonePe, MobiKwik, Navi,
Amazon and Paytm. The framework is fail-closed: unsupported providers are
rejected, and the underlying browser client still refuses to operate without a
live flag, consent record and confirmed probe.
"""

from __future__ import annotations

import sys
from pathlib import Path
from typing import Any, Type

_SYS_DIR = str(Path(__file__).resolve().parent)
if _SYS_DIR not in sys.path:
    sys.path.insert(0, _SYS_DIR)

import provider


class ProviderError(provider.Failure):
    """Provider-specific failure that keeps the provider.Failure API."""

    def __init__(
        self,
        code: str,
        http_status: int = 502,
        retry_after: int = 0,
        detail: str = "",
    ) -> None:
        super().__init__(code, http_status=http_status, retry_after=retry_after, detail=detail)
        self.code = code
        self.http_status = http_status
        self.retry_after = retry_after
        self.detail = detail


Stage = provider.Stage
PROBE_RESULT = provider.PROBE_RESULT


class BaseProvider:
    """Minimal provider interface shared by all UPI consumers."""

    provider_name = "Base"

    def __init__(self, probe_result: dict[str, Any] | None = None) -> None:
        self.probe_result = probe_result or {}
        self._allowed_hosts: frozenset[str] = frozenset()
        host = str(self.probe_result.get("host") or "").lower().strip()
        if host:
            self._allowed_hosts = frozenset({host})

    @staticmethod
    def get_provider_name() -> str:
        raise NotImplementedError("Subclasses must implement get_provider_name()")

    def _host_allowed(self, url: str) -> bool:
        if not self._allowed_hosts:
            return False
        host = (provider.urlparse(url).hostname or "").lower()
        return host in self._allowed_hosts

    def start_session(self, phone: str) -> tuple[Any, Stage]:
        state, stage = provider.start(phone)
        if isinstance(state, dict):
            state["_provider_name"] = self.get_provider_name()
            state["_provider_impl"] = self
        return state, stage

    def submit_otp(self, state: dict[str, Any], otp: str) -> Stage:
        return provider.submit_otp(state, otp)

    def read_history(self, state: dict[str, Any], since_iso: str) -> dict[str, Any]:
        return provider.read_history(state, since_iso)

    def close(self, state: dict[str, Any]) -> None:
        provider.close(state)

    @staticmethod
    def classify(text: str, path: str = "") -> Stage:
        return provider.classify(text, path)


class ConsumerBrowserProvider(BaseProvider):
    """Common browser-login consumer flow used by OTP-based UPI apps."""

    def start_session(self, phone: str) -> tuple[Any, Stage]:
        return super().start_session(phone)


class PhonePeProvider(ConsumerBrowserProvider):
    @staticmethod
    def get_provider_name() -> str:
        return "PhonePe"


class MobiKwikProvider(ConsumerBrowserProvider):
    @staticmethod
    def get_provider_name() -> str:
        return "MobiKwik"


class NaviProvider(ConsumerBrowserProvider):
    @staticmethod
    def get_provider_name() -> str:
        return "Navi"


class AmazonProvider(ConsumerBrowserProvider):
    @staticmethod
    def get_provider_name() -> str:
        return "Amazon"


class PaytmProvider(ConsumerBrowserProvider):
    @staticmethod
    def get_provider_name() -> str:
        return "Paytm"


# ---------------------------------------------------------------------------
# Lab feeder — integration fixture, NOT a provider
# ---------------------------------------------------------------------------

#: Real providers only. The lab feeder is a fixture and is deliberately absent
#: from this tuple so a 501 message can never imply it is a provider.
SUPPORTED_PROVIDER_NAMES = ("Paytm", "PhonePe", "MobiKwik", "Navi", "Amazon")

#: Marker the feeder is required to send. Anything else is refused.
FEEDER_SOURCE = "LAB_FEEDER"

#: Reasons a feeder reply can be rejected. Kept explicit so the HTTP layer can
#: report precisely why a fixture was refused rather than a generic failure.
FEEDER_PROVENANCE_REJECTED = "FEEDER_PROVENANCE_REJECTED"
FEEDER_NOT_CONFIGURED = "FEEDER_NOT_CONFIGURED"


def _require_synthetic(payload: dict[str, Any], origin: str) -> dict[str, Any]:
    """Accept a feeder payload only if it declares itself synthetic.

    The contract with ``lab-feeder`` is deliberately narrow. A reply qualifies
    only when it carries every one of these:

    * ``source == "LAB_FEEDER"``
    * ``providerAuthorized`` present and exactly ``False``
    * ``synthetic`` present and exactly ``True``
    * ``acceptanceEligible`` present and exactly ``False``

    Anything missing is refused. The permissive default on every flag is the
    hostile case: a payload that omits a flag must not be treated as compliant,
    because a future change to the feeder could otherwise silently start
    claiming authorization.
    """
    if not isinstance(payload, dict):
        raise ProviderError(
            FEEDER_PROVENANCE_REJECTED,
            http_status=502,
            detail=f"{origin}: payload is not an object",
        )
    if payload.get("source") != FEEDER_SOURCE:
        raise ProviderError(
            FEEDER_PROVENANCE_REJECTED,
            http_status=502,
            detail=f"{origin}: source is {payload.get('source')!r}, expected {FEEDER_SOURCE!r}",
        )
    if payload.get("providerAuthorized") is not False:
        raise ProviderError(
            FEEDER_PROVENANCE_REJECTED,
            http_status=502,
            detail=f"{origin}: providerAuthorized must be exactly false",
        )
    if payload.get("synthetic") is not True:
        raise ProviderError(
            FEEDER_PROVENANCE_REJECTED,
            http_status=502,
            detail=f"{origin}: synthetic must be exactly true",
        )
    if payload.get("acceptanceEligible") is not False:
        raise ProviderError(
            FEEDER_PROVENANCE_REJECTED,
            http_status=502,
            detail=f"{origin}: acceptanceEligible must be exactly false",
        )
    return payload


def _stamp(payload: dict[str, Any], row_count: int) -> dict[str, Any]:
    """Attach the non-negotiable provenance markers to a feeder result."""
    return {
        **payload,
        "source": FEEDER_SOURCE,
        "providerAuthorized": False,
        "synthetic": True,
        # Belt and braces: the caller receives this even if the upstream
        # payload somehow dropped the field, so no consumer can treat a
        # LabFeeder result as acceptance evidence.
        "acceptanceEligible": False,
        "count": row_count,
    }


class LabFeederProvider(BaseProvider):
    """Reads synthetic rows from the on-device lab feeder over Binder.

    This exists so the client-side bind lifecycle, AIDL marshalling and
    transaction-windowing logic can be exercised without a provider account.

    It is deliberately NOT a ``ConsumerBrowserProvider``. It cannot open a
    browser, it never satisfies the Phase 0.5 probe, and every result it returns
    carries ``acceptanceEligible=False``. A successful ``read_history`` here
    proves the plumbing works; it does not prove an own-UPI or statement read,
    and must never be recorded as one.

    The transport is injected rather than imported so this module keeps its
    no-device-dependency property and the gates can drive it with a fake.
    """

    provider_name = "LabFeeder"

    #: Set by :func:`set_feeder_transport` before any session starts.
    _transport: Any = None

    def __init__(self, probe_result: dict[str, Any] | None = None) -> None:
        super().__init__(probe_result)
        # A feeder session never derives its host allowlist from the probe; it
        # talks to no remote host at all.
        self._allowed_hosts = frozenset()

    @staticmethod
    def get_provider_name() -> str:
        return "LabFeeder"

    def start_session(self, phone: str) -> tuple[Any, Stage]:
        if type(self)._transport is None:
            raise ProviderError(
                FEEDER_NOT_CONFIGURED,
                http_status=503,
                detail="no lab feeder transport registered",
            )
        if not phone.isdigit() or not (10 <= len(phone) <= 13):
            raise ProviderError(
                "PHONE_INVALID", http_status=400,
                detail="phone must be 10-13 digits",
            )
        state = {
            "_provider_name": self.get_provider_name(),
            "_provider_impl": self,
            "phone": phone,
            "own_upis": [],
            "page": 1,
        }
        # Fail closed if even the UPI list cannot be provenance-checked.
        state["own_upis"] = self._read_upis()
        return state, Stage("session_started", ok=True)

    def _read_upis(self) -> list[str]:
        payload = type(self)._transport("getUPIList")
        checked = _require_synthetic(payload, "getUPIList")
        data = checked.get("data") or {}
        items = data.get("items") or []
        return [str(i.get("vpa")) for i in items if isinstance(i, dict) and i.get("vpa")]

    def read_history(self, state: dict[str, Any], since_iso: str) -> dict[str, Any]:
        """Read a time window from the feeder, provenance-checked.

        ``since_iso`` is accepted for interface parity; the feeder takes epoch
        milliseconds, so the value is parsed and used as the window start. A
        payload that fails the synthetic check raises rather than degrading,
        because a silent fallback here would hide exactly the mistake this
        class exists to prevent.
        """
        start_ms = _iso_to_epoch_millis(since_iso)
        if start_ms is None:
            raise ProviderError(
                "SINCE_UNPARSEABLE", http_status=400,
                detail=f"since={since_iso!r} is not an ISO timestamp",
            )
        end_ms = int(state.get("now_ms") or _now_millis())
        if start_ms > end_ms:
            raise ProviderError(
                "SINCE_IN_FUTURE", http_status=400,
                detail="since is after the current time",
            )

        payload = type(self)._transport(
            "getPayListByTimeStamp", start_ms, end_ms,
        )
        checked = _require_synthetic(payload, "getPayListByTimeStamp")
        data = checked.get("data") or {}
        rows = data.get("rows") or []
        if not isinstance(rows, list):
            raise ProviderError(
                FEEDER_PROVENANCE_REJECTED, http_status=502,
                detail="rows is not a list",
            )
        return _stamp(checked, len(rows))

    def submit_otp(self, state: dict[str, Any], otp: str) -> Stage:
        """Not applicable: the feeder has no login.

        A feeder fixture must never be advanced with an OTP-shaped value, so
        this refuses rather than no-op'ing.
        """
        raise ProviderError(
            "OTP_NOT_APPLICABLE", http_status=400,
            detail="the lab feeder has no OTP stage",
        )

    def close(self, state: dict[str, Any]) -> None:
        state.pop("_provider_impl", None)


def set_feeder_transport(transport: Any) -> None:
    """Register the callable used to reach the feeder (test or device bridge)."""
    LabFeederProvider._transport = transport


def feeder_is_configured() -> bool:
    return LabFeederProvider._transport is not None


def _now_millis() -> int:
    import time
    return int(time.time() * 1000)


def _iso_to_epoch_millis(value: str) -> int | None:
    if not value:
        return 0
    from datetime import datetime, timezone
    text = str(value).strip().replace("Z", "+00:00")
    try:
        parsed = datetime.fromisoformat(text)
    except ValueError:
        return None
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return int(parsed.timestamp() * 1000)


_PROVIDER_REGISTRY: dict[str, Type[BaseProvider]] = {
    "phonepe": PhonePeProvider,
    "mobikwik": MobiKwikProvider,
    "navi": NaviProvider,
    "amazon": AmazonProvider,
    "paytm": PaytmProvider,
    # Fixture, listed separately from the real providers so it is never
    # confused with one by a reader skimming this table.
    "labfeeder": LabFeederProvider,
}


def _normalize_name(provider_name: str | None) -> str:
    value = (provider_name or "").strip().lower().replace("-", "").replace("_", "").replace(" ", "")
    return value


def get_provider(provider_name: str | None) -> Type[BaseProvider] | None:
    normalized = _normalize_name(provider_name)
    if not normalized:
        return PaytmProvider
    return _PROVIDER_REGISTRY.get(normalized)


def build_provider(provider_name: str | None) -> BaseProvider:
    cls = get_provider(provider_name)
    if cls is not None:
        return cls()
    if provider_name is None or _normalize_name(provider_name) == "":
        return PaytmProvider()
    raise ProviderError(
        "PROVIDER_NOT_IMPLEMENTED",
        http_status=501,
        detail=f"{provider_name} has no client; "
               f"real providers: {', '.join(SUPPORTED_PROVIDER_NAMES)}",
    )


def load_probe() -> dict[str, Any]:
    return provider.load_probe()


def start(phone: str, provider_name: str = "Paytm") -> tuple[Any, Stage]:
    impl = build_provider(provider_name)
    return impl.start_session(phone)


def submit_otp(state: dict[str, Any], otp: str) -> Stage:
    if isinstance(state, dict) and "_provider_impl" in state:
        return state["_provider_impl"].submit_otp(state, otp)
    return provider.submit_otp(state, otp)


def read_history(state: dict[str, Any], since_iso: str) -> dict[str, Any]:
    if isinstance(state, dict) and "_provider_impl" in state:
        return state["_provider_impl"].read_history(state, since_iso)
    return provider.read_history(state, since_iso)


def close(state: dict[str, Any]) -> None:
    if isinstance(state, dict) and "_provider_impl" in state:
        state["_provider_impl"].close(state)
        return
    provider.close(state)


__all__ = [
    "BaseProvider",
    "AmazonProvider",
    "MobiKwikProvider",
    "NaviProvider",
    "PaytmProvider",
    "PhonePeProvider",
    "PROBE_RESULT",
    "ProviderError",
    "Stage",
    "build_provider",
    "close",
    "get_provider",
    "load_probe",
    "read_history",
    "start",
    "submit_otp",
]
