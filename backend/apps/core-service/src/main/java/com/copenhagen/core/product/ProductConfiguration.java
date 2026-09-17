package com.copenhagen.core.product;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import com.copenhagen.core.common.merchant.MerchantRestClients;

@Configuration
@EnableConfigurationProperties(MerchantsProperties.class)
public class ProductConfiguration {

    @Bean
    public Map<String, MerchantCatalogClient> merchantCatalogClients(MerchantsProperties properties) {
        Map<String, MerchantCatalogClient> clients = new LinkedHashMap<>();
        for (MerchantsProperties.MerchantConfig config : properties.merchants()) {
            RestClient restClient = MerchantRestClients.create(config);
            clients.put(config.id(), new MerchantCatalogClient(
                    config.id(), config.displayName(), config.baseUrl().toString(), restClient));
        }
        return Map.copyOf(clients);
    }
}
