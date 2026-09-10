package com.copenhagen.merchantmock.catalog;

import com.copenhagen.merchantmock.support.ApiExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Catalog 세 엔드포인트의 HTTP 계약. DB 없이 standalone MockMvc 로 돌린다. */
class CatalogControllerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        CatalogStore store = new CatalogStore(CatalogFixture::seed);
        store.load();
        this.mockMvc = MockMvcBuilders
                .standaloneSetup(new CatalogController(new CatalogService(store)))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void 검색이_items를_돌려준다() throws Exception {
        mockMvc.perform(post("/ucp/v1/catalog/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"러닝화\",\"filters\":{\"maxPrice\":200000,\"currency\":\"KRW\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].productId").value("product-101"))
                .andExpect(jsonPath("$.items[0].title").value("러닝화 A"))
                .andExpect(jsonPath("$.items[0].price.amount").value(89000))
                .andExpect(jsonPath("$.items[0].price.currency").value("KRW"))
                .andExpect(jsonPath("$.items[0].availability").value("IN_STOCK"));
    }

    @Test
    void 검색_결과가_없으면_빈_배열이고_200이다() throws Exception {
        mockMvc.perform(post("/ucp/v1/catalog/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"없는상품\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void 일괄조회가_요청한_순서로_준다() throws Exception {
        mockMvc.perform(post("/ucp/v1/catalog/lookup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productIds\":[\"product-104\",\"product-101\",\"product-999\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].productId").value("product-104"))
                .andExpect(jsonPath("$.items[1].productId").value("product-101"));
    }

    @Test
    void 상세조회가_옵션까지_돌려준다() throws Exception {
        mockMvc.perform(post("/ucp/v1/catalog/product")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":\"product-102\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productId").value("product-102"))
                .andExpect(jsonPath("$.price.amount").value(129000))
                .andExpect(jsonPath("$.options[0].name").value("size"))
                .andExpect(jsonPath("$.options[0].values.length()").value(3))
                .andExpect(jsonPath("$.options[0].values[0].optionValueId").value("product-102-m-black"))
                .andExpect(jsonPath("$.availability").value("IN_STOCK"));
    }

    @Test
    void 없는_상품은_404와_오류_Envelope이다() throws Exception {
        mockMvc.perform(post("/ucp/v1/catalog/product")
                        .header("X-Request-Id", "req-test-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":\"product-999\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"))
                .andExpect(jsonPath("$.requestId").value("req-test-1"))
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void 단종_상품도_404다() throws Exception {
        mockMvc.perform(post("/ucp/v1/catalog/product")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":\"product-105\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
    }
}
