package com.dealership.appointmentreminder.service;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.dealership.appointmentreminder.dto.AppointmentDetails;
import com.dealership.appointmentreminder.entity.Channel;

import static org.assertj.core.api.Assertions.assertThat;

class RequestHasherTest {

	private static final Instant WHEN = Instant.parse("2026-10-08T15:00:00Z");

	@Test
	void identicalRequestsHashTheSame() {
		assertThat(RequestHasher.hash(details("Alex", "Civic"))).isEqualTo(RequestHasher.hash(details("Alex", "Civic")))
			.hasSize(64);
	}

	@Test
	void anyChangedFieldChangesTheHash() {
		assertThat(RequestHasher.hash(details("Alex", "Civic"))).isNotEqualTo(RequestHasher.hash(details("Alex", "Accord")));
	}

	@Test
	void fieldBoundariesCannotBeShiftedToForgeACollision() {
		// Plain concatenation would turn both into "AlexCivic".
		assertThat(RequestHasher.hash(details("Alex", "Civic"))).isNotEqualTo(RequestHasher.hash(details("AlexC", "ivic")));
	}

	@Test
	void aMissingFieldIsNotTheSameAsAnEmptyOne() {
		assertThat(RequestHasher.hash(details("Alex", null))).isNotEqualTo(RequestHasher.hash(details("Alex", "")));
	}

	private static AppointmentDetails details(String name, String vehicle) {
		return new AppointmentDetails(1L, name, "+13125550142", null, Channel.SMS, vehicle, null, WHEN);
	}

}
