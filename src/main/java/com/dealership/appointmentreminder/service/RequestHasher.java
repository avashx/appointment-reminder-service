package com.dealership.appointmentreminder.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import com.dealership.appointmentreminder.dto.AppointmentDetails;

/**
 * Fingerprint of a booking request, stored with its Idempotency-Key. If the same key comes back
 * with a different body, the client has a bug (or reused a key), and we must not pretend it is a replay.
 */
final class RequestHasher {

	private RequestHasher() {
	}

	static String hash(AppointmentDetails details) {
		StringBuilder canonical = new StringBuilder();
		append(canonical, details.dealershipId());
		append(canonical, details.customerName());
		append(canonical, details.customerPhone());
		append(canonical, details.customerEmail());
		append(canonical, details.preferredChannel());
		append(canonical, details.vehicle());
		append(canonical, details.serviceType());
		append(canonical, details.scheduledAt()); // an Instant, so the offset used in the request doesn't matter
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is required by every JVM", ex);
		}
	}

	/** Length-prefixed, so ("a|b", "c") and ("a", "b|c") can never produce the same string. */
	private static void append(StringBuilder canonical, Object value) {
		if (value == null) {
			canonical.append("null;");
			return;
		}
		String text = value.toString();
		canonical.append(text.length()).append(':').append(text).append(';');
	}

}
