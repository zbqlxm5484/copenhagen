package com.copenhagen.merchantmock.checkout;

import com.copenhagen.merchantmock.checkout.CheckoutMessages.Checkout;
import com.copenhagen.merchantmock.checkout.CheckoutMessages.CheckoutRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Checkout 생성·조회·수정. 전체 구매 19단계 중 6단계다(DESIGN.md 3.3절).
 *
 * <p>Complete 와 Cancel 은 176·182 에서 이 클래스에 붙인다.
 */
@RestController
@RequestMapping(path = "/ucp/v1/checkout-sessions", produces = MediaType.APPLICATION_JSON_VALUE)
public class CheckoutController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final CheckoutService service;

    public CheckoutController(CheckoutService service) {
        this.service = service;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Checkout> create(
            @RequestBody CheckoutRequest request,
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request, idempotencyKey));
    }

    @GetMapping(path = "/{checkoutId}")
    public Checkout get(@PathVariable String checkoutId) {
        return service.get(checkoutId);
    }

    @PutMapping(path = "/{checkoutId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Checkout update(@PathVariable String checkoutId, @RequestBody CheckoutRequest request) {
        return service.update(checkoutId, request);
    }
}
