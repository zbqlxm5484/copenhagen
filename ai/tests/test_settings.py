"""Settings tests use explicit environment mappings and synthetic credentials."""

import builtins
import json
from pathlib import Path
import socket
import traceback

import pytest
from pydantic import ValidationError

from copenhagen_fds import settings as settings_module
from copenhagen_fds.settings import EvaluationMode, SettingsError, load_settings


@pytest.fixture
def live_environment():
    return {
        "FDS_MODE": "LIVE",
        "GMS_KEY": "synthetic-gms-private-value",
        "FDS_SIFT_API_KEY": "synthetic-sift-private-value",
        "FDS_OPENAI_TIMEOUT_SECONDS": "5.5",
        "FDS_SIFT_TIMEOUT_SECONDS": "3",
        "FDS_EVALUATION_TIMEOUT_SECONDS": "10",
        "FDS_POLICY_VERSION": "test-policy-v1",
        "FDS_SIFT_MEDIUM_THRESHOLD": "0.4",
        "FDS_SIFT_HIGH_THRESHOLD": "0.8",
        "FDS_SIFT_SCORE_UNIT": "SIFT_PAYMENT_ABUSE_0_1",
    }


def test_mock_needs_no_keys_or_assumed_operating_policy():
    settings = load_settings({})
    assert settings.mode == EvaluationMode.MOCK
    assert settings.openai_model == "gpt-5.4-mini"
    assert settings.gms_key is None
    assert settings.sift_api_key is None
    assert settings.openai_timeout_seconds is None
    assert settings.sift_timeout_seconds is None
    assert settings.evaluation_timeout_seconds is None
    assert settings.sift_thresholds is None
    assert settings.policy_version is None


def test_example_is_usable_without_credentials():
    example = Path(__file__).resolve().parents[1] / ".env.example"
    environment = {}
    for line in example.read_text(encoding="utf-8").splitlines():
        if line and not line.startswith("#"):
            name, value = line.split("=", 1)
            environment[name] = value
    assert environment["GMS_KEY"] == ""
    assert environment["FDS_SIFT_API_KEY"] == ""
    assert load_settings(environment) == load_settings({})


def test_live_reads_explicit_settings_without_calling_providers(live_environment, monkeypatch):
    def forbidden_network(*args, **kwargs):
        pytest.fail("settings loading must not contact a provider")

    monkeypatch.setattr(socket, "create_connection", forbidden_network)
    settings = load_settings(live_environment)
    assert settings.mode == EvaluationMode.LIVE
    assert settings.openai_model == "gpt-5.4-mini"
    assert settings.openai_timeout_seconds == 5.5
    assert settings.sift_timeout_seconds == 3.0
    assert settings.evaluation_timeout_seconds == 10.0
    assert settings.policy_version == "test-policy-v1"
    assert settings.sift_thresholds.medium.value == 0.4
    assert settings.sift_thresholds.high.value == 0.8
    assert settings.sift_thresholds.medium.unit == "SIFT_PAYMENT_ABUSE_0_1"
    assert settings.gms_key.get_secret_value() == live_environment["GMS_KEY"]


def test_model_changes_only_when_explicitly_configured():
    settings = load_settings({"FDS_OPENAI_MODEL": "explicit-test-model"})
    assert settings.openai_model == "explicit-test-model"


def test_gms_gateway_and_chat_completions_match_the_connection_contract():
    settings = load_settings({})
    assert settings.openai_base_url == "https://gms.ssafy.io/gmsapi/api.openai.com/v1"
    assert settings.openai_chat_completions_url == (
        "https://gms.ssafy.io/gmsapi/api.openai.com/v1/chat/completions"
    )
    assert settings.openai_chat_completions_url == settings.openai_base_url + "/chat/completions"


def test_direct_openai_process_defaults_do_not_override_gms(live_environment, monkeypatch):
    environment = {
        **live_environment,
        "OPENAI_API_KEY": "synthetic-direct-key",
        "OPENAI_BASE_URL": "https://api.openai.com/v1",
    }
    monkeypatch.setattr(settings_module.os, "environ", environment)
    settings = load_settings()
    assert settings.gms_key.get_secret_value() == live_environment["GMS_KEY"]
    assert settings.sift_api_key.get_secret_value() == live_environment["FDS_SIFT_API_KEY"]
    assert settings.openai_base_url == "https://gms.ssafy.io/gmsapi/api.openai.com/v1"


def test_direct_openai_key_cannot_satisfy_missing_gms_key(live_environment):
    live_environment.pop("GMS_KEY")
    live_environment["OPENAI_API_KEY"] = "synthetic-direct-key"
    with pytest.raises(SettingsError) as caught:
        load_settings(live_environment)
    assert "GMS_KEY" in caught.value.fields


@pytest.mark.parametrize("legacy_value", ["", "synthetic-legacy-key"])
@pytest.mark.parametrize("with_gms_key", [False, True])
def test_legacy_fds_key_requires_explicit_migration(legacy_value, with_gms_key):
    environment = {"FDS_OPENAI_API_KEY": legacy_value}
    if with_gms_key:
        environment["GMS_KEY"] = "synthetic-gms-key"
    with pytest.raises(SettingsError, match="replace the legacy") as caught:
        load_settings(environment)
    assert caught.value.fields == ("FDS_OPENAI_API_KEY", "GMS_KEY")
    assert "synthetic-legacy-key" not in str(caught.value)
    assert "synthetic-gms-key" not in str(caught.value)
    assert caught.value.__context__ is None


@pytest.mark.parametrize("name", ["gms_key", "Gms_Key", "GMS_key"])
def test_gms_variable_name_is_case_sensitive(name):
    with pytest.raises(SettingsError) as caught:
        load_settings({name: "synthetic-gms-key"})
    assert caught.value.fields == ("GMS_KEY",)
    assert "synthetic-gms-key" not in str(caught.value)


def test_empty_mapping_does_not_inherit_process_secrets(live_environment, monkeypatch):
    monkeypatch.setattr(settings_module.os, "environ", live_environment)
    assert load_settings().mode == EvaluationMode.LIVE
    assert load_settings({}).mode == EvaluationMode.MOCK
    assert load_settings({}).gms_key is None


def test_loading_does_not_read_dotenv_or_secret_files(monkeypatch):
    def forbidden_file(*args, **kwargs):
        pytest.fail("settings loading must not open files")

    monkeypatch.setattr(builtins, "open", forbidden_file)
    assert load_settings({}).mode == EvaluationMode.MOCK


def test_loading_does_not_mutate_or_cache_environment(live_environment):
    original = dict(live_environment)
    first = load_settings(live_environment)
    assert live_environment == original
    live_environment["FDS_POLICY_VERSION"] = "test-policy-v2"
    second = load_settings(live_environment)
    assert first.policy_version == "test-policy-v1"
    assert second.policy_version == "test-policy-v2"


@pytest.mark.parametrize("field", [
    "GMS_KEY", "FDS_SIFT_API_KEY", "FDS_OPENAI_TIMEOUT_SECONDS",
    "FDS_SIFT_TIMEOUT_SECONDS", "FDS_EVALUATION_TIMEOUT_SECONDS", "FDS_POLICY_VERSION",
    "FDS_SIFT_MEDIUM_THRESHOLD", "FDS_SIFT_HIGH_THRESHOLD", "FDS_SIFT_SCORE_UNIT",
])
@pytest.mark.parametrize("blank", [False, True])
def test_live_rejects_missing_or_empty_required_settings(live_environment, field, blank):
    if blank:
        live_environment[field] = ""
    else:
        live_environment.pop(field)
    with pytest.raises(SettingsError) as caught:
        load_settings(live_environment)
    assert field in caught.value.fields


def test_switching_only_mode_to_live_does_not_enable_an_incomplete_configuration():
    with pytest.raises(SettingsError) as caught:
        load_settings({"FDS_MODE": "LIVE"})
    assert "GMS_KEY" in caught.value.fields
    assert "FDS_SIFT_API_KEY" in caught.value.fields
    assert "FDS_SIFT_MEDIUM_THRESHOLD" in caught.value.fields


@pytest.mark.parametrize("value", ["", "live", "mock", "SANDBOX", "UNKNOWN", " LIVE "])
def test_invalid_mode_never_falls_back_to_mock(value):
    with pytest.raises(SettingsError) as caught:
        load_settings({"FDS_MODE": value})
    assert caught.value.fields == ("FDS_MODE",)


@pytest.mark.parametrize("value", ["", "   "])
def test_blank_model_never_falls_back_to_default(value):
    with pytest.raises(SettingsError):
        load_settings({"FDS_OPENAI_MODEL": value})


@pytest.mark.parametrize("field", ["FDS_OPENAI_TIMEOUT_SECONDS", "FDS_SIFT_TIMEOUT_SECONDS", "FDS_EVALUATION_TIMEOUT_SECONDS"])
@pytest.mark.parametrize("value", ["0", "-1", "nan", "inf", "-inf", "abc", "true"])
def test_invalid_timeouts_are_rejected_even_in_mock(field, value):
    with pytest.raises(SettingsError) as caught:
        load_settings({field: value})
    assert field in caught.value.fields


@pytest.mark.parametrize("field", ["FDS_OPENAI_TIMEOUT_SECONDS", "FDS_SIFT_TIMEOUT_SECONDS"])
def test_provider_timeout_cannot_exceed_total_budget(live_environment, field):
    live_environment[field] = "10.01"
    with pytest.raises(SettingsError) as caught:
        load_settings(live_environment)
    assert field in caught.value.fields
    assert "FDS_EVALUATION_TIMEOUT_SECONDS" in caught.value.fields


def test_provider_timeout_can_equal_total_budget(live_environment):
    live_environment["FDS_OPENAI_TIMEOUT_SECONDS"] = "10"
    assert load_settings(live_environment).openai_timeout_seconds == 10.0


@pytest.mark.parametrize("field", ["FDS_SIFT_MEDIUM_THRESHOLD", "FDS_SIFT_HIGH_THRESHOLD"])
@pytest.mark.parametrize("value", ["-0.1", "1.1", "50", "nan", "inf", "false"])
def test_thresholds_use_only_finite_sift_api_values(live_environment, field, value):
    live_environment[field] = value
    with pytest.raises(SettingsError) as caught:
        load_settings(live_environment)
    assert field in caught.value.fields


@pytest.mark.parametrize("medium,high", [("0.8", "0.8"), ("0.9", "0.8")])
def test_thresholds_must_be_strictly_ordered(live_environment, medium, high):
    live_environment.update(FDS_SIFT_MEDIUM_THRESHOLD=medium, FDS_SIFT_HIGH_THRESHOLD=high)
    with pytest.raises(SettingsError, match="lower than"):
        load_settings(live_environment)


def test_threshold_endpoints_and_explicit_zero_are_preserved(live_environment):
    live_environment.update(FDS_SIFT_MEDIUM_THRESHOLD="0", FDS_SIFT_HIGH_THRESHOLD="1")
    settings = load_settings(live_environment)
    assert settings.sift_thresholds.medium.value == 0.0
    assert settings.sift_thresholds.high.value == 1.0


@pytest.mark.parametrize("unit", ["PERCENT", "PROBABILITY", "SIFT_PAYMENT_ABUSE_0_100"])
def test_threshold_scale_cannot_be_relabelled(live_environment, unit):
    live_environment["FDS_SIFT_SCORE_UNIT"] = unit
    with pytest.raises(SettingsError) as caught:
        load_settings(live_environment)
    assert caught.value.fields == ("FDS_SIFT_SCORE_UNIT",)


def test_mock_thresholds_still_require_an_explicit_policy_version(live_environment):
    live_environment["FDS_MODE"] = "MOCK"
    live_environment.pop("FDS_POLICY_VERSION")
    with pytest.raises(SettingsError) as caught:
        load_settings(live_environment)
    assert caught.value.fields == ("FDS_POLICY_VERSION",)


@pytest.mark.parametrize("field", ["GMS_KEY", "FDS_SIFT_API_KEY"])
@pytest.mark.parametrize("value", [" ", "test key", "test\nkey", " test-key"])
def test_nonempty_keys_cannot_contain_whitespace(field, value):
    with pytest.raises(SettingsError) as caught:
        load_settings({field: value})
    assert caught.value.fields == (field,)


@pytest.mark.parametrize("field,value", [("FDS_MODE", True), ("GMS_KEY", 123), ("FDS_SIFT_HIGH_THRESHOLD", 0.8)])
def test_environment_mapping_rejects_non_string_values(field, value):
    with pytest.raises(SettingsError, match="strings"):
        load_settings({field: value})


@pytest.mark.parametrize("field", ["FDS_MOD", "fds_mode", "FDS_OPENAI_TIMEOUT_SECOND"])
def test_unrecognized_fds_names_do_not_silently_use_defaults(field):
    with pytest.raises(SettingsError, match="unrecognized"):
        load_settings({field: "LIVE"})


def test_unrelated_environment_variables_are_ignored():
    assert load_settings({"PATH": "unrelated-value"}) == load_settings({})


def test_secret_values_are_masked_in_settings_output(live_environment):
    settings = load_settings(live_environment)
    outputs = [
        str(settings), repr(settings), repr(settings.model_dump()), settings.model_dump_json(),
        json.dumps(settings.model_dump(mode="json")), repr(settings.gms_key),
    ]
    for key in ("GMS_KEY", "FDS_SIFT_API_KEY"):
        assert all(live_environment[key] not in output for output in outputs)
    assert settings.model_dump(mode="json")["gms_key"] == "**********"
    assert settings.model_dump(mode="json")["sift_api_key"] == "**********"


@pytest.mark.parametrize("invalid_field,invalid_value", [
    ("FDS_MODE", "private-marker"), ("FDS_OPENAI_TIMEOUT_SECONDS", "private-marker"),
    ("GMS_KEY", "private-marker with whitespace"),
    ("FDS_SIFT_MEDIUM_THRESHOLD", "private-marker"), ("FDS_SIFT_SCORE_UNIT", "private-marker"),
    ("FDS_SIFT_HIGH_THRESHOLD", "0.1"), ("FDS_POLICY_VERSION", ""),
])
def test_settings_errors_never_retain_secret_values_or_raw_exception_chain(live_environment, invalid_field, invalid_value):
    live_environment[invalid_field] = invalid_value
    with pytest.raises(SettingsError) as caught:
        load_settings(live_environment)
    error = caught.value
    outputs = [str(error), repr(error), repr(error.args), repr(vars(error)), "".join(traceback.format_exception(error))]
    for private in ("private-marker", "synthetic-gms-private-value", "synthetic-sift-private-value"):
        assert all(private not in output for output in outputs)
    assert error.__cause__ is None
    assert error.__context__ is None


def test_loaded_settings_prevent_reassignment():
    settings = load_settings({})
    with pytest.raises(ValidationError, match="frozen"):
        settings.mode = EvaluationMode.LIVE
