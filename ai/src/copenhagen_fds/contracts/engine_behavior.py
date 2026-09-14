"""Complete behavior-stage evidence, ready for the later engine orchestration."""

from typing import Literal, Self

from pydantic import Field, model_validator

from copenhagen_fds.contracts.engine_inputs import EngineEvaluationRequest, EngineRequestReference
from copenhagen_fds.contracts.personal_evaluation import PersonalizedEvaluationResult
from copenhagen_fds.contracts.personalization_inputs import PersonalizedScoringInput
from copenhagen_fds.contracts.values import InputModel
from copenhagen_fds.engine_behavior_inputs import validate_engine_behavior_input


class EngineBehaviorResult(InputModel):
    """Retain the existing scorer's coverage, stages and failures without projection."""
    schema_version: Literal["engine-behavior-result-v1"] = "engine-behavior-result-v1"
    request: EngineEvaluationRequest = Field(repr=False)
    reference: EngineRequestReference
    scoring_input: PersonalizedScoringInput = Field(repr=False)
    evaluation: PersonalizedEvaluationResult = Field(repr=False)

    @model_validator(mode="after")
    def validate_binding(self) -> Self:
        request = EngineEvaluationRequest.model_validate(self.request.model_dump())
        if self.reference != EngineRequestReference.from_request(request):
            raise ValueError("behavior execution must preserve its original engine request")
        validate_engine_behavior_input(request, self.scoring_input)
        result = PersonalizedEvaluationResult.model_validate(self.evaluation.model_dump())
        result.result.validate_for_input(self.scoring_input)
        if result.components.policies != request.scoring:
            raise ValueError("behavior execution must preserve the requested scoring policies")
        return self

    def validate_for_request(self, request: EngineEvaluationRequest) -> Self:
        if self.reference != EngineRequestReference.from_request(request):
            raise ValueError("behavior execution belongs to different request content or versions")
        return type(self).model_validate(self.model_dump())
