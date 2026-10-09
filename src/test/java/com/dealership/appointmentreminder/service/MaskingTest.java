package com.dealership.appointmentreminder.service;

import org.junit.jupiter.api.Test;

import com.dealership.appointmentreminder.entity.Channel;

import static org.assertj.core.api.Assertions.assertThat;

class MaskingTest {

	@Test
	void masksTheMiddleOfAPhoneNumber() {
		assertThat(Masking.maskPhone("+13125550142")).isEqualTo("+1312*****42");
	}

	@Test
	void masksTheLocalPartOfAnEmail() {
		assertThat(Masking.maskEmail("alex@example.com")).isEqualTo("a***@example.com");
	}

	@Test
	void picksTheRuleByChannel() {
		assertThat(Masking.mask(Channel.SMS, "+919876543210")).isEqualTo("+9198******10");
		assertThat(Masking.mask(Channel.EMAIL, "priya@example.in")).isEqualTo("p***@example.in");
	}

	@Test
	void neverLeaksSomethingItCannotParse() {
		assertThat(Masking.maskPhone(null)).isEqualTo("***");
		assertThat(Masking.maskPhone("+1234")).isEqualTo("***");
		assertThat(Masking.maskEmail("not-an-email")).isEqualTo("***");
		assertThat(Masking.maskEmail(null)).isEqualTo("***");
	}

}
