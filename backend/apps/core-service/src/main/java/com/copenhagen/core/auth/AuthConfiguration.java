package com.copenhagen.core.auth;

import java.time.Clock;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.copenhagen.core.common.crypto.PiiEncryptor;
import com.copenhagen.core.common.crypto.PiiHasher;

@Configuration
public class AuthConfiguration {

    @Bean
    public Clock coreClock() {
        return Clock.systemUTC();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * 키가 없으면 여기서 기동이 실패한다. 운영 프로필에 기본값을 두지 않는 것이 의도된 동작이다.
     * 로컬·테스트 프로필만 개발 전용 고정값을 갖는다.
     */
    @Bean
    public PiiEncryptor piiEncryptor(@Value("${core.pii.encryption-key}") String encryptionKey) {
        return new PiiEncryptor(encryptionKey);
    }

    @Bean
    public PiiHasher piiHasher(@Value("${core.pii.hash-pepper}") String hashPepper) {
        return new PiiHasher(hashPepper);
    }
}
