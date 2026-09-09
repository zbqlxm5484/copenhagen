package com.copenhagen.pay.fraud.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SiftFraudPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration.class)
            .withInitializer(context -> {
                context.getEnvironment().getPropertySources()
                        .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
                context.getEnvironment().getPropertySources()
                        .remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
                try {
                    new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"))
                            .forEach(source -> context.getEnvironment().getPropertySources().addLast(source));
                } catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                }
            });

    @Test
    void bind_applicationDefaults_keepsSecretsAndThresholdsUnconfigured() {
        contextRunner.run(context -> {
            assertNull(context.getStartupFailure());
            SiftFraudProperties properties = context.getBean(SiftFraudProperties.class);
            assertEquals("", properties.apiKey());
            assertEquals("", properties.accountId());
            assertEquals("sandbox", properties.env());
            assertEquals(Duration.ofSeconds(3), properties.timeout());
            assertNull(properties.reviewThreshold());
            assertNull(properties.denyThreshold());
            assertTrue(context.getBean(FraudDetectionProperties.class).enabled());
        });
    }

    @Test
    void bind_environmentOverrides_preservesValuesAndRedactsCredentials() {
        contextRunner.withPropertyValues(
                "SIFT_API_KEY=test-sift-key-not-a-secret",
                "SIFT_ACCOUNT_ID=test-account",
                "SIFT_ENV=production",
                "SIFT_TIMEOUT_SECONDS=7",
                "SIFT_REVIEW_THRESHOLD=0.40",
                "SIFT_DENY_THRESHOLD=0.85",
                "FRAUD_DETECTION_ENABLED=false")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    SiftFraudProperties properties = context.getBean(SiftFraudProperties.class);
                    assertEquals("test-sift-key-not-a-secret", properties.apiKey());
                    assertEquals("test-account", properties.accountId());
                    assertEquals("production", properties.env());
                    assertEquals(Duration.ofSeconds(7), properties.timeout());
                    assertEquals(new BigDecimal("0.40"), properties.reviewThreshold());
                    assertEquals(new BigDecimal("0.85"), properties.denyThreshold());
                    assertFalse(context.getBean(FraudDetectionProperties.class).enabled());
                    assertFalse(properties.toString().contains(properties.apiKey()));
                    assertFalse(properties.toString().contains(properties.accountId()));
                });
    }

    @Test
    void bind_thresholdBoundaries_acceptsZeroAndOne() {
        contextRunner.withPropertyValues("SIFT_REVIEW_THRESHOLD=0", "SIFT_DENY_THRESHOLD=1")
                .run(context -> assertNull(context.getStartupFailure()));
    }

    @ParameterizedTest
    @MethodSource("invalidConfigurations")
    void bind_invalidConfiguration_failsStartup(String[] properties) {
        contextRunner.withPropertyValues(properties)
                .run(context -> assertNotNull(context.getStartupFailure()));
    }

    private static Stream<Arguments> invalidConfigurations() {
        return Stream.of(
                new String[]{"SIFT_TIMEOUT_SECONDS=0"},
                new String[]{"SIFT_TIMEOUT_SECONDS=-1"},
                new String[]{"SIFT_ENV=unknown"},
                new String[]{"SIFT_REVIEW_THRESHOLD=0.4"},
                new String[]{"SIFT_DENY_THRESHOLD=0.8"},
                new String[]{"SIFT_REVIEW_THRESHOLD=-0.1", "SIFT_DENY_THRESHOLD=0.8"},
                new String[]{"SIFT_REVIEW_THRESHOLD=0.4", "SIFT_DENY_THRESHOLD=1.1"},
                new String[]{"SIFT_REVIEW_THRESHOLD=0.8", "SIFT_DENY_THRESHOLD=0.8"},
                new String[]{"SIFT_REVIEW_THRESHOLD=0.9", "SIFT_DENY_THRESHOLD=0.8"},
                new String[]{"SIFT_REVIEW_THRESHOLD=40", "SIFT_DENY_THRESHOLD=85"},
                new String[]{"SIFT_REVIEW_THRESHOLD=NaN", "SIFT_DENY_THRESHOLD=1"},
                new String[]{"FRAUD_DETECTION_ENABLED=invalid"}
        ).map(properties -> Arguments.of((Object) properties));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({SiftFraudProperties.class, FraudDetectionProperties.class})
    static class PropertiesConfiguration {
    }
}
