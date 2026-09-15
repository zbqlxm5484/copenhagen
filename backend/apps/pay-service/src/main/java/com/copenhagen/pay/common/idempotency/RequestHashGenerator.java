package com.copenhagen.pay.common.idempotency;

import com.copenhagen.pay.common.error.PayErrorCode;
import com.copenhagen.pay.common.error.PayException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import org.erdtman.jcs.JsonCanonicalizer;

@Component
public class RequestHashGenerator {

    private final ObjectMapper objectMapper;

    public RequestHashGenerator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String generate(HttpServletRequest request, Object body) {
        return generateBody(body);
    }

    public String generate(
            String method,
            String path,
            java.util.Map<String, ? extends java.util.Collection<String>> queryParameters,
            Object body) {
        return generateBody(body);
    }

    public String generateBody(Object body) {
        try {
            return generateBody(objectMapper.writeValueAsString(body).getBytes(StandardCharsets.UTF_8));
        } catch (IllegalArgumentException exception) {
            throw invalidBodyHash(exception);
        }
    }

    public String generateBody(byte[] body) {
        byte[] json = body == null || body.length == 0
                ? "null".getBytes(StandardCharsets.UTF_8)
                : body;
        try {
            JsonCanonicalizer canonicalizer = new JsonCanonicalizer(json);
            return sha256(canonicalizer.getEncodedUTF8());
        } catch (IOException | RuntimeException exception) {
            throw invalidBodyHash(exception);
        }
    }

    private PayException invalidBodyHash(Throwable cause) {
        return new PayException(
                PayErrorCode.INVALID_REQUEST,
                "요청 본문을 해시할 수 없습니다.",
                null,
                "CHECK_REQUEST",
                cause);
    }

    private String sha256(byte[] value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(digest.digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the runtime", exception);
        }
    }
}
