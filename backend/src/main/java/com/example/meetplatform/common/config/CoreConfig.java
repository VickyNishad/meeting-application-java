package com.example.meetplatform.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
public class CoreConfig {

    /** All time-dependent logic takes the clock from here so tests can pin "now". */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /** bcrypt by default, with {id} prefixes so the algorithm can be upgraded without a migration. */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }
}
