package com.vkplatform.iam.platform.time;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
public class ClockConfiguration {
    @Bean
    Clock iamClock() {
        return Clock.systemUTC();
    }
}
