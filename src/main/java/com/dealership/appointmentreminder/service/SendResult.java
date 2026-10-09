package com.dealership.appointmentreminder.service;

/**
 * What happened to one hand-off. Sealed, so a {@code switch} over it must handle every case. The
 * split that matters is "definitely not delivered" (the two rejections, which free the idempotency
 * key for a real retry) versus "may have been delivered" (Unknown, which keeps the key taken).
 */
public sealed interface SendResult {

	/** The provider took responsibility for the message, e.g. HTTP 201/202. */
	record Accepted(String providerMessageId) implements SendResult {
	}

	/**
	 * Nothing was sent, because attempt {@code handedOverByAttempt} already handed this idempotency
	 * key to the provider. {@code providerMessageId} is null if that attempt never learned the
	 * provider's answer (it crashed, or got Unknown). Only {@link IdempotentNotificationSender}
	 * returns this.
	 */
	record Suppressed(int handedOverByAttempt, String providerMessageId) implements SendResult {
	}

	/**
	 * The provider did NOT accept the message and a retry may work, e.g. HTTP 429, or connection
	 * refused (the request never left this machine).
	 */
	record RetryableRejection(String reason) implements SendResult {
	}

	/** The provider did NOT accept the message and never will, e.g. an invalid number or an opted-out recipient. */
	record PermanentRejection(String reason) implements SendResult {
	}

	/**
	 * We can't know: read timeout, connection reset after the request was sent, HTTP 5xx. The
	 * provider may have delivered it, so the idempotency key stays taken and no later attempt
	 * hands it over again.
	 */
	record Unknown(String reason) implements SendResult {
	}

}
