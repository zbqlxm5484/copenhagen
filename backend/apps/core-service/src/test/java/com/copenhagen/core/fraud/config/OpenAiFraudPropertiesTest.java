package com.copenhagen.core.fraud.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

class OpenAiFraudPropertiesTest {

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
    void bind_applicationDefaults_registersConfigurationWithoutApiKey() {
        contextRunner.run(context -> {
            assertNull(context.getStartupFailure());
            OpenAiFraudProperties properties = context.getBean(OpenAiFraudProperties.class);
            assertEquals("", properties.apiKey());
            assertEquals("gpt-5.4-mini", properties.model());
            assertEquals(Duration.ofSeconds(5), properties.timeout());
        });
    }

    @Test
    void bind_environmentOverrides_preservesValuesAndRedactsKey() {
        contextRunner.withPropertyValues(
                "OPENAI_API_KEY=test-openai-key-not-a-secret",
                "OPENAI_FRAUD_MODEL=gpt-5.4-mini-test",
                "OPENAI_TIMEOUT_SECONDS=8")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    OpenAiFraudProperties properties = context.getBean(OpenAiFraudProperties.class);
                    assertEquals("test-openai-key-not-a-secret", properties.apiKey());
                    assertEquals("gpt-5.4-mini-test", properties.model());
                    assertEquals(Duration.ofSeconds(8), properties.timeout());
                    assertFalse(properties.toString().contains(properties.apiKey()));
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "OPENAI_TIMEOUT_SECONDS=0",
        "OPENAI_TIMEOUT_SECONDS=-1",
        "OPENAI_TIMEOUT_SECONDS=invalid",
        "OPENAI_FRAUD_MODEL="
    })
    void bind_invalidConfiguration_failsStartup(String property) {
        contextRunner.withPropertyValues(property)
                .run(context -> assertNotNull(context.getStartupFailure()));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(OpenAiFraudProperties.class)
    static class PropertiesConfiguration {
    }
}
