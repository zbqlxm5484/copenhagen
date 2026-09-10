package com.copenhagen.merchantmock.support;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** ApiException 을 오류 Envelope 으로 바꾼다. requestId 는 Core 가 보낸 X-Request-Id 를 되돌려준다. */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private static final String REQUEST_ID_HEADER = "X-Request-Id";

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handle(ApiException e, HttpServletRequest request) {
        String requestId = request.getHeader(REQUEST_ID_HEADER);
        LOG.info("요청 거절. code={} status={} requestId={}", e.code(), e.status().value(), requestId);
        return ResponseEntity.status(e.status())
                .body(new ErrorResponse(e.code(), e.getMessage(), requestId));
    }
}
