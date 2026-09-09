package com.copenhagen.merchantmock.catalog;

import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * products 테이블을 읽는 구현. JdbcTemplate 만 쓰고 JPA 를 쓰지 않는다(DESIGN.md 4절).
 *
 * <p>variants_json 은 jsonb 라 문자열로 꺼내 Jackson 으로 푼다.
 */
@Repository
public class JdbcProductRepository implements ProductRepository {

    private static final String SELECT_ALL = """
            SELECT id, external_id, title, image_url, base_price, currency, status, variants_json::text
              FROM products
             ORDER BY external_id
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public JdbcProductRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<Product> findAll() {
        return jdbcTemplate.query(SELECT_ALL, rowMapper());
    }

    private RowMapper<Product> rowMapper() {
        return (rs, rowNum) -> new Product(
                rs.getString("id"),
                rs.getString("external_id"),
                rs.getString("title"),
                null,
                rs.getString("image_url"),
                rs.getLong("base_price"),
                rs.getString("currency"),
                Product.ProductStatus.valueOf(rs.getString("status")),
                parseVariants(rs.getString("variants_json")));
    }

    private List<Product.Variant> parseVariants(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonNode array = objectMapper.readTree(json);
            List<Product.Variant> variants = new ArrayList<>();
            for (JsonNode node : array) {
                variants.add(new Product.Variant(
                        node.path("sku").asText(),
                        node.path("optionName").asText(),
                        node.path("optionValue").asText(),
                        node.path("priceDelta").asLong(0L),
                        node.path("stock").asInt(0),
                        Product.ProductStatus.valueOf(node.path("status").asText("ACTIVE"))));
            }
            return List.copyOf(variants);
        } catch (Exception e) {
            throw new IllegalStateException("products.variants_json 을 읽을 수 없다: " + json, e);
        }
    }
}
