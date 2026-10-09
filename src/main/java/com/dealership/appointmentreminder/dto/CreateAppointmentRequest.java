package com.dealership.appointmentreminder.dto;

import java.time.OffsetDateTime;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import com.dealership.appointmentreminder.entity.Channel;

/**
 * Body of POST /appointments. {@code scheduledAt} must carry an offset (e.g. -05:00 or Z): a bare
 * local time is ambiguous, especially around daylight-saving changes.
 */
public record CreateAppointmentRequest(
		@NotNull @Positive Long dealershipId,
		@NotNull @Valid Customer customer,
		@Size(max = 200) String vehicle,
		@Size(max = 100) String serviceType,
		@NotNull OffsetDateTime scheduledAt) {

	public record Customer(
			@NotBlank @Size(max = 200) String name,
			@Pattern(regexp = "^\\+[1-9]\\d{6,14}$", message = "must be in E.164 format, e.g. +13125550142") String phone,
			@Email @Size(max = 254) String email,
			Channel preferredChannel) {

		@JsonIgnore
		@AssertTrue(message = "phone or email is required")
		public boolean isContactPresent() {
			return hasText(this.phone) || hasText(this.email);
		}

		@JsonIgnore
		@AssertTrue(message = "SMS needs a phone number and EMAIL needs an email address")
		public boolean isPreferredChannelUsable() {
			if (this.preferredChannel == null) {
				return true;
			}
			return (this.preferredChannel == Channel.SMS) ? hasText(this.phone) : hasText(this.email);
		}

		/** Explicit choice if given, otherwise SMS when there is a phone number, else EMAIL. */
		Channel resolvedChannel() {
			if (this.preferredChannel != null) {
				return this.preferredChannel;
			}
			return hasText(this.phone) ? Channel.SMS : Channel.EMAIL;
		}

	}

	public AppointmentDetails toDetails() {
		return new AppointmentDetails(this.dealershipId, this.customer.name().trim(), trimToNull(this.customer.phone()),
				normalizeEmail(this.customer.email()), this.customer.resolvedChannel(), trimToNull(this.vehicle),
				trimToNull(this.serviceType), this.scheduledAt.toInstant());
	}

	private static boolean hasText(String value) {
		return value != null && !value.isBlank();
	}

	private static String trimToNull(String value) {
		return hasText(value) ? value.trim() : null;
	}

	private static String normalizeEmail(String email) {
		return hasText(email) ? email.trim().toLowerCase() : null;
	}

}
