package com.dealership.appointmentreminder.entity;

/** What happened to one claim of a reminder. */
public enum AttemptOutcome {

	/** Claimed; the outcome has not been recorded yet. */
	STARTED,

	/** Handed to the provider, which accepted it. */
	ACCEPTED,

	/** Not sent: an earlier attempt had already handed this idempotency key to the provider. */
	SUPPRESSED,

	/** The provider definitely did not accept it, and a retry may work (e.g. 429). */
	RETRYABLE,

	/** The provider definitely did not accept it, and never will (e.g. invalid number). */
	REJECTED,

	/** Handed to the provider, but its answer is unknown (timeout, connection reset). */
	UNKNOWN,

	/** The worker's lease expired before it recorded an outcome (crash or hang); the reminder was reclaimed. */
	ABANDONED

}
