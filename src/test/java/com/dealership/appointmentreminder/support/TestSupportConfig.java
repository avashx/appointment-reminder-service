package com.dealership.appointmentreminder.support;

import java.time.Instant;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Replaces the real clock and the logging stub with versions the tests control and observe. */
@TestConfiguration(proxyBeanMethods = false)
public class TestSupportConfig {

	/** "Now" at the start of every test: Tue 6 Oct 2026, 09:00 UTC. */
	public static final Instant T0 = Instant.parse("2026-10-06T09:00:00Z");

	@Bean
	@Primary
	public MutableClock testClock() {
		return new MutableClock(T0);
	}

	/**
	 * Deliberately not {@code @Primary}: it takes the place of the {@code @Fallback} logging stub as
	 * the provider BEHIND {@code IdempotentNotificationSender}, so every test goes through the real guard.
	 */
	@Bean
	public RecordingNotificationSender recordingNotificationSender() {
		return new RecordingNotificationSender();
	}

}
