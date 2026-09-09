package com.copenhagen.contract.internalapi.fraud;

/** Shared outcome vocabulary; an outcome is not a payment authorization. */
public enum FraudDecisionType {
    ALLOW,
    REQUIRE_AUTH,
    DENY
}
