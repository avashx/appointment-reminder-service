package com.dealership.appointmentreminder.entity;

/**
 * Lifecycle of one reminder. Only PENDING and PROCESSING are non-terminal. PROCESSING goes back
 * to PENDING in two cases: a retry was scheduled, or the lease expired and the row was reclaimed.
 * Either way the next attempt goes through the delivery record, so it can't send twice.
 */
public enum ReminderStatus {

	/** Waiting for {@code next_attempt_at}. */
	PENDING,

	/** Claimed by exactly one worker until {@code processing_until}; a send may be in flight. */
	PROCESSING,

	/** Handed to the provider exactly once, and the provider accepted it (or an earlier attempt already had). */
	SENT,

	/** Not delivered: permanent rejection, or retries exhausted. */
	FAILED,

	/** Its due time had already passed when the appointment was booked. */
	SKIPPED,

	/** Picked up after its lateness window, e.g. after an outage. */
	EXPIRED,

	/** The appointment was cancelled first. */
	CANCELLED,

	/** The appointment was rescheduled; a reminder for the new revision replaces it. */
	SUPERSEDED

}
