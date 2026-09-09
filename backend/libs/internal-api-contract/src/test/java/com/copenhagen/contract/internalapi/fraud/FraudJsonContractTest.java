package com.copenhagen.contract.internalapi.fraud;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.exc.InvalidFormatException;
import tools.jackson.databind.exc.ValueInstantiationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FraudJsonContractTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void roundTrip_purchaseIntent_preservesCamelCaseMoneyAndInstants() {
        PurchaseIntent original = FraudFixtures.intent();
        String json = mapper.writeValueAsString(original);
        JsonNode tree = mapper.readTree(json);

        assertEquals(original, mapper.readValue(json, PurchaseIntent.class));
        assertEquals("intent-test", tree.get("intentId").asText());
        assertEquals(100000L, tree.get("maxTotalAmount").asLong());
        assertTrue(tree.get("maxTotalAmount").isIntegralNumber());
        assertFalse(tree.has("intent_id"));
    }

    @ParameterizedTest
    @EnumSource(FraudDecisionType.class)
    void roundTrip_decision_preservesNestedSchemaAndEnumNames(FraudDecisionType type) {
        FraudDecision original = new FraudDecision(
                type, RiskLevel.MEDIUM, FraudFixtures.sift(), FraudFixtures.analysis(),
                List.of(FraudFixtures.rule()), List.of("TEST_REASON"),
                "테스트 결과", FraudFixtures.CREATED_AT, "trace-test");
        String json = mapper.writeValueAsString(original);
        JsonNode tree = mapper.readTree(json);

        assertEquals(original, mapper.readValue(json, FraudDecision.class));
        assertEquals(type.name(), tree.get("decision").asText());
        assertEquals("MEDIUM", tree.get("riskLevel").asText());
        assertEquals("CRITICAL", tree.get("rules").get(0).get("severity").asText());
        assertEquals(true, tree.get("intentAnalysis").get("intentMatch").asBoolean());
    }

    @Test
    void roundTrip_unavailableSift_preservesNullSignals() {
        SiftFraudResult original = FraudFixtures.unavailableSift();
        String json = mapper.writeValueAsString(original);
        assertEquals(original, mapper.readValue(json, SiftFraudResult.class));
        assertTrue(mapper.readTree(json).get("score").isNull());
    }

    @Test
    void deserialize_missingBooleansOrUnknownEnum_rejectsInput() {
        assertThrows(ValueInstantiationException.class, () -> mapper.readValue(
                "{\"semanticRisk\":\"LOW\",\"summary\":\"summary\"}", IntentAnalysisResult.class));
        assertThrows(ValueInstantiationException.class, () -> mapper.readValue(
                "{\"ruleId\":\"rule\",\"severity\":\"LOW\",\"reason\":\"reason\"}", RuleResult.class));
        assertThrows(InvalidFormatException.class, () -> mapper.readValue("\"HOLD\"", FraudDecisionType.class));
    }
}
