package com.copenhagen.merchantmock.profile;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Business Profile Discovery. 전체 구매 19단계 중 2단계다.
 *
 * <p>이 경로만 루트에 있다. 나머지 아홉 개는 /ucp/v1 아래다(DESIGN.md 3.3절).
 */
@RestController
public class BusinessProfileController {

    private final BusinessProfileService service;

    public BusinessProfileController(BusinessProfileService service) {
        this.service = service;
    }

    @GetMapping(path = "/.well-known/ucp", produces = MediaType.APPLICATION_JSON_VALUE)
    public BusinessProfileResponse businessProfile() {
        return service.build();
    }
}
