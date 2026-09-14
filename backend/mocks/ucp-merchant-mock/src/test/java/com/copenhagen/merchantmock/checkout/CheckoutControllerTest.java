package com.copenhagen.merchantmock.checkout;

import com.copenhagen.merchantmock.checkout.CheckoutTestSupport.Harness;
import com.copenhagen.merchantmock.support.ApiExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 체크아웃 네 엔드포인트의 HTTP 계약. 필드 이름이 Core 확정본과 같은 snake_case 인지가 핵심이다. */
class CheckoutControllerTest {

    private static final String CREATE_BODY = """
            {
              "line_items": [
                { "product_id": "product-101", "option_value_ids": ["product-101-270"], "quantity": 1 }
              ],
              "fulfillment": {
                "type": "shipping",
                "destination": {
                  "recipient": "홍길동",
                  "postal_code": "06236",
                  "address_line1": "서울특별시 강남구 테헤란로 1",
                  "address_line2": "101호"
                }
              }
            }
            """;

    private static final String NO_ADDRESS_BODY = """
            {
              "line_items": [
                { "product_id": "product-101", "option_value_ids": ["product-101-270"], "quantity": 1 }
              ]
            }
            """;

    private MockMvc mockMvc;
    private Harness harness;

    @BeforeEach
    void setUp() {
        this.harness = CheckoutTestSupport.harness();
        this.mockMvc = MockMvcBuilders
                .standaloneSetup(new CheckoutController(harness.service()))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void 생성은_201_이고_Core_확정본_모양이다() throws Exception {
        mockMvc.perform(post("/ucp/v1/checkout-sessions")
                        .header("Idempotency-Key", "checkout-create-abc")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Content-Type", MediaType.APPLICATION_JSON_VALUE))
                .andExpect(jsonPath("$.id").isString())
                .andExpect(jsonPath("$.status").value("ready_for_complete"))
                .andExpect(jsonPath("$.currency").value("KRW"))
                .andExpect(jsonPath("$.line_items[0].id").value("line-1"))
                .andExpect(jsonPath("$.line_items[0].item.id").value("product-101"))
                .andExpect(jsonPath("$.line_items[0].item.title").value("러닝화 A"))
                .andExpect(jsonPath("$.line_items[0].item.price").value(89000))
                .andExpect(jsonPath("$.line_items[0].quantity").value(1))
                .andExpect(jsonPath("$.fulfillment.type").value("shipping"))
                .andExpect(jsonPath("$.fulfillment.status").value("available"))
                .andExpect(jsonPath("$.fulfillment.amount").value(3000))
                .andExpect(jsonPath("$.totals[0].type").value("subtotal"))
                .andExpect(jsonPath("$.totals[0].amount").value(89000))
                .andExpect(jsonPath("$.totals[1].type").value("fulfillment"))
                .andExpect(jsonPath("$.totals[2].type").value("total"))
                .andExpect(jsonPath("$.totals[2].amount").value(92000))
                .andExpect(jsonPath("$.expires_at").value(CheckoutTestSupport.EXPECTED_EXPIRES_AT))
                .andExpect(jsonPath("$.messages").doesNotExist())
                .andExpect(jsonPath("$.lineItems").doesNotExist());
    }

    @Test
    void 배송지_없는_생성은_201_incomplete_와_messages_다() throws Exception {
        mockMvc.perform(post("/ucp/v1/checkout-sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(NO_ADDRESS_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("incomplete"))
                .andExpect(jsonPath("$.messages[0].code").value("ADDRESS_REQUIRED"))
                .andExpect(jsonPath("$.messages[0].severity").value("error"))
                .andExpect(jsonPath("$.messages[0].target").value("fulfillment.destination"))
                .andExpect(jsonPath("$.fulfillment").doesNotExist())
                .andExpect(jsonPath("$.expires_at").doesNotExist());
    }

    @Test
    void 같은_멱등_키로_두_번_보내면_같은_id_다() throws Exception {
        String first = createAndReadId("checkout-create-same");
        String second = createAndReadId("checkout-create-same");

        org.junit.jupiter.api.Assertions.assertEquals(first, second);
        org.junit.jupiter.api.Assertions.assertEquals(1, harness.repository().size());
    }

    @Test
    void 조회는_생성_응답과_같은_스냅샷이다() throws Exception {
        String id = createAndReadId("checkout-create-get");

        mockMvc.perform(get("/ucp/v1/checkout-sessions/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.status").value("ready_for_complete"))
                .andExpect(jsonPath("$.totals[2].amount").value(92000))
                .andExpect(jsonPath("$.expires_at").value(CheckoutTestSupport.EXPECTED_EXPIRES_AT));
    }

    @Test
    void 없는_체크아웃_조회는_404_오류_Envelope_이다() throws Exception {
        mockMvc.perform(get("/ucp/v1/checkout-sessions/{id}", "nope")
                        .header("X-Request-Id", "req-404"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("CHECKOUT_NOT_FOUND"))
                .andExpect(jsonPath("$.nextAction").value("RETURN_TO_CART"));
    }

    @Test
    void 수정은_200_이고_배송지를_채우면_ready_for_complete_다() throws Exception {
        MvcResult created = mockMvc.perform(post("/ucp/v1/checkout-sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(NO_ADDRESS_BODY))
                .andExpect(jsonPath("$.status").value("incomplete"))
                .andReturn();
        String id = readId(created);

        mockMvc.perform(put("/ucp/v1/checkout-sessions/{id}", id)
                        .header("Idempotency-Key", "checkout-update-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.status").value("ready_for_complete"))
                .andExpect(jsonPath("$.fulfillment.amount").value(3000))
                .andExpect(jsonPath("$.messages").doesNotExist());
    }

    @Test
    void 없는_상품으로_생성하면_404_PRODUCT_NOT_FOUND_다() throws Exception {
        mockMvc.perform(post("/ucp/v1/checkout-sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY.replace("product-101\"", "product-999\"")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("PRODUCT_NOT_FOUND"));
    }

    @Test
    void 없는_체크아웃_수정은_404_다() throws Exception {
        mockMvc.perform(put("/ucp/v1/checkout-sessions/{id}", "nope")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("CHECKOUT_NOT_FOUND"));
    }

    @Test
    void 취소는_200_이고_두_필드만_돌려준다() throws Exception {
        String id = createAndReadId("checkout-create-1");

        mockMvc.perform(post("/ucp/v1/checkout-sessions/{id}/cancel", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.status").value("canceled"))
                .andExpect(jsonPath("$.totals").doesNotExist())
                .andExpect(jsonPath("$.line_items").doesNotExist());

        mockMvc.perform(get("/ucp/v1/checkout-sessions/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("canceled"));
    }

    @Test
    void 없는_체크아웃_취소는_404_다() throws Exception {
        mockMvc.perform(post("/ucp/v1/checkout-sessions/{id}/cancel", "nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("CHECKOUT_NOT_FOUND"))
                .andExpect(jsonPath("$.nextAction").value("RETURN_TO_CART"));
    }

    private String createAndReadId(String idempotencyKey) throws Exception {
        MvcResult result = mockMvc.perform(post("/ucp/v1/checkout-sessions")
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY))
                .andExpect(status().isCreated())
                .andReturn();
        return readId(result);
    }

    private String readId(MvcResult result) throws Exception {
        return harness.objectMapper()
                .readTree(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
                .path("id").asString();
    }
}
