"""Explicit environment loading with typed settings and redacted failures."""

from collections.abc import Mapping
from enum import StrEnum
import os
from typing import Annotated, Any, Self

from pydantic import BeforeValidator, Field, SecretStr, ValidationError, field_validator, model_validator

from copenhagen_fds.contracts.results import SiftScore
from copenhagen_fds.contracts.values import InputModel, Text, enum_parser


class EvaluationMode(StrEnum):
    MOCK = "MOCK"
    LIVE = "LIVE"


class SettingsError(Exception):
    """Contains setting names and a fixed explanation, never input values."""

    def __init__(self, fields: tuple[str, ...], explanation: str = "invalid configuration"):
        self.fields = fields
        super().__init__(f"FDS settings: {explanation}; check {', '.join(fields)}")


class SiftThresholds(InputModel):
    """Explicit cutoffs on the existing Sift API scale; no classification here."""

    medium: SiftScore
    high: SiftScore

    @model_validator(mode="after")
    def validate_order(self) -> Self:
        if self.medium.value >= self.high.value:
            raise SettingsError(
                ("FDS_SIFT_MEDIUM_THRESHOLD", "FDS_SIFT_HIGH_THRESHOLD"),
                "medium threshold must be lower than high threshold",
            )
        return self


Timeout = Annotated[float, Field(gt=0.0, allow_inf_nan=False)]
ModeValue = Annotated[EvaluationMode, BeforeValidator(enum_parser(EvaluationMode))]

ENV_FIELDS = {
    "mode": "FDS_MODE",
    "openai_model": "FDS_OPENAI_MODEL",
    "openai_api_key": "FDS_OPENAI_API_KEY",
    "sift_api_key": "FDS_SIFT_API_KEY",
    "openai_timeout_seconds": "FDS_OPENAI_TIMEOUT_SECONDS",
    "sift_timeout_seconds": "FDS_SIFT_TIMEOUT_SECONDS",
    "evaluation_timeout_seconds": "FDS_EVALUATION_TIMEOUT_SECONDS",
    "policy_version": "FDS_POLICY_VERSION",
}
THRESHOLD_ENV = ("FDS_SIFT_MEDIUM_THRESHOLD", "FDS_SIFT_HIGH_THRESHOLD", "FDS_SIFT_SCORE_UNIT")
SECRET_FIELDS = ("openai_api_key", "sift_api_key")
TIMEOUT_FIELDS = ("openai_timeout_seconds", "sift_timeout_seconds", "evaluation_timeout_seconds")


class FdsSettings(InputModel):
    """Use load_settings for environment parsing and sanitized validation errors."""

    mode: ModeValue = EvaluationMode.MOCK
    openai_model: Text = "gpt-5.4-mini"
    openai_api_key: SecretStr | None = None
    sift_api_key: SecretStr | None = None
    openai_timeout_seconds: Timeout | None = None
    sift_timeout_seconds: Timeout | None = None
    evaluation_timeout_seconds: Timeout | None = None
    policy_version: Text | None = None
    sift_thresholds: SiftThresholds | None = None

    @field_validator("openai_api_key", "sift_api_key")
    @classmethod
    def validate_secret(cls, value: SecretStr | None) -> SecretStr | None:
        if value is not None:
            secret = value.get_secret_value()
            if not secret or any(character.isspace() for character in secret):
                raise ValueError("API key must be nonempty and contain no whitespace")
        return value

    @model_validator(mode="after")
    def validate_combination(self) -> Self:
        if self.mode == EvaluationMode.LIVE:
            required = (*SECRET_FIELDS, *TIMEOUT_FIELDS, "policy_version")
            missing = tuple(ENV_FIELDS[field] for field in required if getattr(self, field) is None)
            if self.sift_thresholds is None:
                missing += THRESHOLD_ENV
            if missing:
                raise SettingsError(missing, "LIVE requires explicit connection and policy settings")
        if self.sift_thresholds is not None and self.policy_version is None:
            raise SettingsError(("FDS_POLICY_VERSION",), "thresholds require a policy version")
        if self.evaluation_timeout_seconds is not None:
            for field in TIMEOUT_FIELDS[:2]:
                timeout = getattr(self, field)
                if timeout is not None and timeout > self.evaluation_timeout_seconds:
                    raise SettingsError(
                        (ENV_FIELDS[field], "FDS_EVALUATION_TIMEOUT_SECONDS"),
                        "provider timeout must not exceed the evaluation timeout",
                    )
        return self


def _number(value: Any) -> Any:
    if isinstance(value, str):
        try:
            return float(value)
        except ValueError:
            pass
    return value


def _error_fields(errors: list[dict[str, Any]]) -> tuple[str, ...]:
    names = set()
    for error in errors:
        location = error["loc"]
        field = location[0] if location else None
        if field in ENV_FIELDS:
            names.add(ENV_FIELDS[field])
        elif field == "sift_thresholds":
            if location[-1] == "unit":
                names.add("FDS_SIFT_SCORE_UNIT")
            elif "medium" in location:
                names.add("FDS_SIFT_MEDIUM_THRESHOLD")
            elif "high" in location:
                names.add("FDS_SIFT_HIGH_THRESHOLD")
            else:
                names.update(THRESHOLD_ENV)
        else:
            names.add("FDS_* settings")
    return tuple(sorted(names))


def load_settings(environ: Mapping[str, str] | None = None) -> FdsSettings:
    """Read only the supplied mapping (or os.environ); never read .env or call APIs.

    An explicit empty mapping is isolated from the process environment. Empty
    optional values mean unset, while empty mode/model values are invalid.
    """

    environment = os.environ if environ is None else environ
    allowed = set(ENV_FIELDS.values()) | set(THRESHOLD_ENV)
    if any(isinstance(name, str) and name.upper().startswith("FDS_") and name not in allowed for name in environment):
        raise SettingsError(("FDS_* settings",), "unrecognized environment setting")
    for name in allowed:
        if name in environment and type(environment[name]) is not str:
            raise SettingsError((name,), "environment values must be strings")

    data: dict[str, Any] = {}
    for field, name in ENV_FIELDS.items():
        if name not in environment:
            continue
        value: Any = environment[name]
        if value == "" and field not in ("mode", "openai_model"):
            value = None
        if field in SECRET_FIELDS and value is not None:
            value = SecretStr(value)
        elif field in TIMEOUT_FIELDS:
            value = _number(value)
        data[field] = value

    medium, high, unit = (environment.get(name) or None for name in THRESHOLD_ENV)
    if any(value is not None for value in (medium, high, unit)):
        data["sift_thresholds"] = {
            "medium": {"value": _number(medium), "unit": unit},
            "high": {"value": _number(high), "unit": unit},
        }

    failure = None
    try:
        return FdsSettings.model_validate(data)
    except ValidationError as error:
        failure = SettingsError(_error_fields(error.errors(include_input=False, include_context=False)))
    # Raise outside the handler so raw ValidationError/input are not chained.
    raise failure
