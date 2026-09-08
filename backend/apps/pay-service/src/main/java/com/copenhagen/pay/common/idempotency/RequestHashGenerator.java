package com.copenhagen.pay.common.idempotency;

import com.copenhagen.pay.common.error.PayErrorCode;
import com.copenhagen.pay.common.error.PayException;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Component
public class RequestHashGenerator {

    private final ObjectMapper objectMapper;

    public RequestHashGenerator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String generate(HttpServletRequest request, Object body) {
        Map<String, Collection<String>> queryParameters = request.getParameterMap().entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> Arrays.asList(entry.getValue())));
        return generate(request.getMethod(), request.getRequestURI(), queryParameters, body);
    }

    public String generate(
            String method,
            String path,
            Map<String, ? extends Collection<String>> queryParameters,
            Object body) {
        if (method == null || method.isBlank() || path == null || path.isBlank()) {
            throw new PayException(
                    PayErrorCode.INVALID_REQUEST,
                    "요청 해시를 생성할 요청 정보가 부족합니다.",
                    null,
                    "CHECK_REQUEST");
        }

        String canonicalRequest = String.join(
                "\n",
                method.toUpperCase(java.util.Locale.ROOT),
                path,
                canonicalQuery(queryParameters),
                canonicalBody(body));
        return sha256(canonicalRequest);
    }

    private String canonicalQuery(Map<String, ? extends Collection<String>> queryParameters) {
        if (queryParameters == null || queryParameters.isEmpty()) {
            return "";
        }

        StringJoiner joiner = new StringJoiner("&");
        queryParameters.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    Collection<String> values = entry.getValue() == null
                            ? List.of("")
                            : entry.getValue();
                    values.stream()
                            .sorted(Comparator.nullsFirst(String::compareTo))
                            .forEach(value -> joiner.add(entry.getKey() + "=" + value));
                });
        return joiner.toString();
    }

    private String canonicalBody(Object body) {
        try {
            JsonNode bodyTree = objectMapper.valueToTree(body);
            return objectMapper.writeValueAsString(canonicalize(bodyTree));
        } catch (IllegalArgumentException | JacksonException exception) {
            throw new PayException(
                    PayErrorCode.INVALID_REQUEST,
                    "요청 본문을 해시할 수 없습니다.",
                    null,
                    "CHECK_REQUEST",
                    exception);
        }
    }

    private JsonNode canonicalize(JsonNode node) {
        if (node == null || node.isValueNode()) {
            return node;
        }
        if (node.isObject()) {
            ObjectNode normalized = objectMapper.createObjectNode();
            List<String> fieldNames = new ArrayList<>(node.propertyNames());
            fieldNames.sort(String::compareTo);
            for (String fieldName : fieldNames) {
                normalized.set(fieldName, canonicalize(node.get(fieldName)));
            }
            return normalized;
        }
        if (node.isArray()) {
            ArrayNode normalized = objectMapper.createArrayNode();
            node.forEach(element -> normalized.add(canonicalize(element)));
            return normalized;
        }
        return node;
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(
                    digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the runtime", exception);
        }
    }
}
