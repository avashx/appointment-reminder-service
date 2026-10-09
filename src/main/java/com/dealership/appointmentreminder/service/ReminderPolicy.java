package com.dealership.appointmentreminder.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.stereotype.Component;

import com.dealership.appointmentreminder.config.ReminderProperties;
import com.dealership.appointmentreminder.entity.Reminder;
import com.dealership.appointmentreminder.entity.ReminderStatus;
import com.dealership.appointmentreminder.entity.ReminderType;

/**
 * Every timing rule in one place, as pure functions of their inputs (no clock, no I/O), so each
 * rule is unit-testable with fixed instants. All arithmetic is on Instants: "24 hours before"
 * means 24 elapsed hours, even across a daylight-saving change.
 */
@Component
public class ReminderPolicy {

	public static final String WINDOW_PASSED_AT_BOOKING = "WINDOW_PASSED_AT_BOOKING";

	private final ReminderProperties properties;

	public ReminderPolicy(ReminderProperties properties) {
		this.properties = properties;
	}

	/**
	 * One reminder per type. A reminder whose due time has already passed at booking is SKIPPED:
	 * the customer has just booked, so a "your appointment is tomorrow" message seconds later is noise.
	 */
	public List<Reminder> plan(long appointmentId, int revision, Instant scheduledAt, Instant now) {
		List<Reminder> reminders = new ArrayList<>();
		for (ReminderType type : ReminderType.values()) {
			Instant dueAt = scheduledAt.minus(this.properties.settingsFor(type).offset());
			if (dueAt.isBefore(now)) {
				reminders.add(Reminder.planned(appointmentId, revision, type, dueAt, ReminderStatus.SKIPPED,
						WINDOW_PASSED_AT_BOOKING, now));
			}
			else {
				reminders.add(Reminder.planned(appointmentId, revision, type, dueAt, ReminderStatus.PENDING, null, now));
			}
		}
		return reminders;
	}

	/**
	 * A reminder may go out inside its window {@code [dueAt, dueAt + maxLateness]}, and never once
	 * the appointment has started: a late reminder is better than none, up to a point. With the
	 * default settings the 24H and 2H windows can't overlap, so an outage never produces
	 * back-to-back reminders.
	 */
	public boolean isSendable(ReminderType type, Instant dueAt, Instant appointmentAt, Instant now) {
		Instant windowEnd = dueAt.plus(this.properties.settingsFor(type).maxLateness());
		return !now.isBefore(dueAt) && !now.isAfter(windowEnd) && now.isBefore(appointmentAt);
	}

	/**
	 * When to try again after an attempt that did not end in a delivery, or empty to give up:
	 * either all attempts are used up, or the retry would land outside the sendable window.
	 */
	public Optional<Instant> nextRetryAt(ReminderType type, Instant dueAt, int attemptsSoFar, Instant appointmentAt,
			Instant now) {
		if (attemptsSoFar >= this.properties.worker().maxAttempts()) {
			return Optional.empty();
		}
		Instant retryAt = now.plus(backoff(attemptsSoFar));
		return isSendable(type, dueAt, appointmentAt, retryAt) ? Optional.of(retryAt) : Optional.empty();
	}

	/** Exponential backoff (30s, 60s, 120s, ... capped) with +/-20% jitter so retries don't arrive in lockstep. */
	Duration backoff(int attemptsSoFar) {
		long initialMillis = this.properties.worker().backoffInitial().toMillis();
		long maxMillis = this.properties.worker().backoffMax().toMillis();
		long exponential = initialMillis << Math.min(Math.max(attemptsSoFar - 1, 0), 20);
		long capped = Math.min(exponential, maxMillis);
		double jitter = ThreadLocalRandom.current().nextDouble(0.8, 1.2);
		return Duration.ofMillis((long) (capped * jitter));
	}

}
