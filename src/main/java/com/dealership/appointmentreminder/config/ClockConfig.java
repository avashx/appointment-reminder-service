package com.dealership.appointmentreminder.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The single source of "now". Production code never calls Instant.now() directly, so tests can
 * replace this bean with a clock they control and jump 24 hours ahead in a millisecond.
 */
@Configuration(proxyBeanMethods = false)
class ClockConfig {

	@Bean
	Clock clock() {
		return Clock.systemUTC();
	}

}
