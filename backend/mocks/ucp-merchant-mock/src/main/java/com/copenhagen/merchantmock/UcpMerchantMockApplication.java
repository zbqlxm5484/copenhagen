package com.copenhagen.merchantmock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class UcpMerchantMockApplication {

    public static void main(String[] args) {
        SpringApplication.run(UcpMerchantMockApplication.class, args);
    }
}
