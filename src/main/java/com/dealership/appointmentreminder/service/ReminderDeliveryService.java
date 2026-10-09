package com.dealership.appointmentreminder.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

import com.dealership.appointmentreminder.entity.AttemptOutcome;
import com.dealership.appointmentreminder.repository.NotificationAttemptRepository;
import com.dealership.appointmentreminder.repository.NotificationDeliveryRepository;
import com.dealership.appointmentreminder.repository.ReminderRepository;

/**
 * Steps 3 and 4 of the dispatch cycle for one claimed reminder: hand it to the sender with no
 * transaction open (Step 3), then record the outcome in a short transaction (Step 4). Every Step 4
 * write is fenced by the claim token: if the lease expired and another worker reclaimed the
 * reminder meanwhile, the write matches 0 rows and that worker's state is left alone.
 */
@Service
public class ReminderDeliveryService {

	private static final Logger log = LoggerFactory.getLogger(ReminderDeliveryService.class);

	private static final int OUTCOME_WRITE_ATTEMPTS = 3;

	private final NotificationSender sender;

	private final ReminderRepository reminders;

	private final NotificationAttemptRepository attempts;

	private final NotificationDeliveryRepository deliveries;

	private final ReminderPolicy policy;

	private final TransactionTemplate transactions;

	private final Clock clock;

	public ReminderDeliveryService(NotificationSender sender, ReminderRepository reminders,
			NotificationAttemptRepository attempts, NotificationDeliveryRepository deliveries, ReminderPolicy policy,
			TransactionTemplate transactions, Clock clock) {
		this.sender = sender;
		this.reminders = reminders;
		this.attempts = attempts;
		this.deliveries = deliveries;
		this.policy = policy;
		this.transactions = transactions;
		this.clock = clock;
	}

	/** Like {@link #deliver}, but a failure is logged rather than thrown, so one bad reminder can't stop a batch. */
	public void deliverSafely(ClaimedReminder claimed) {
		try {
			deliver(claimed);
		}
		catch (RuntimeException ex) {
			log.error("REMINDER_DELIVERY_FAILED reminderId={} key={} attempt={}: stays PROCESSING until its lease expires at {}, then it is reclaimed",
					claimed.reminderId(), claimed.idempotencyKey(), claimed.attemptNo(), claimed.processingUntil(), ex);
		}
	}

	public void deliver(ClaimedReminder claimed) {
		// Only start a send while holding the lease; after it, another worker may already own the reminder.
		if (!this.clock.instant().isBefore(claimed.processingUntil())) {
			log.warn("REMINDER_LEASE_EXPIRED_BEFORE_SEND reminderId={} key={} attempt={}: not sent, it will be reclaimed",
					claimed.reminderId(), claimed.idempotencyKey(), claimed.attemptNo());
			return;
		}
		SendResult result = this.sender.send(claimed.notification());
		recordOutcome(claimed, result);
	}

	/**
	 * Retried a few times, because the hand-off has already happened. If it still fails, the reminder
	 * stays PROCESSING; the lease expires, it is reclaimed, and the delivery record suppresses the
	 * resend. Losing the outcome can therefore only cost a reminder, never duplicate one.
	 */
	private void recordOutcome(ClaimedReminder claimed, SendResult result) {
		for (int attempt = 1;; attempt++) {
			try {
				this.transactions.executeWithoutResult((status) -> applyOutcome(claimed, result));
				return;
			}
			catch (DataAccessException | TransactionException ex) {
				if (attempt == OUTCOME_WRITE_ATTEMPTS) {
					throw ex;
				}
				log.warn("REMINDER_OUTCOME_WRITE_RETRY reminderId={} attempt={} error={}", claimed.reminderId(), attempt,
						ex.getMessage());
				pause(Duration.ofMillis(200L * attempt));
			}
		}
	}

	private void applyOutcome(ClaimedReminder claimed, SendResult result) {
		Instant now = this.clock.instant();
		switch (result) {
			case SendResult.Accepted accepted ->
				sent(claimed, AttemptOutcome.ACCEPTED, accepted.providerMessageId(), null, now);
			case SendResult.Suppressed suppressed ->
				sent(claimed, AttemptOutcome.SUPPRESSED, suppressed.providerMessageId(), describe(suppressed), now);
			case SendResult.RetryableRejection rejection ->
				notSent(claimed, AttemptOutcome.RETRYABLE, "rejected: " + rejection.reason(), true, now);
			case SendResult.PermanentRejection rejection ->
				notSent(claimed, AttemptOutcome.REJECTED, "rejected permanently: " + rejection.reason(), false, now);
			case SendResult.Unknown unknown -> mayHaveBeenSent(claimed, unknown.reason(), now);
		}
	}

	private void sent(ClaimedReminder claimed, AttemptOutcome outcome, String providerMessageId, String note,
			Instant now) {
		int updated = this.reminders.markSent(claimed.reminderId(), claimed.claimToken(), providerMessageId, note, now);
		complete(claimed, outcome, providerMessageId, note, now);
		if (updated == 0) {
			stale(claimed, outcome);
			return;
		}
		AfterCommit.run(() -> log.info("REMINDER_SENT reminderId={} key={} attempt={} outcome={} providerMessageId={}{}",
				claimed.reminderId(), claimed.idempotencyKey(), claimed.attemptNo(), outcome, providerMessageId,
				(note != null) ? " note=" + note : ""));
	}

	/**
	 * The provider definitely did not accept it, so nothing was delivered. The delivery record is
	 * released in the same fenced transaction, so the retry really sends instead of being suppressed.
	 */
	private void notSent(ClaimedReminder claimed, AttemptOutcome outcome, String reason, boolean retryable,
			Instant now) {
		Optional<Instant> retryAt = retryable ? nextRetryAt(claimed, now) : Optional.empty();
		int updated = retryAt.isPresent()
				? this.reminders.releaseForRetry(claimed.reminderId(), claimed.claimToken(), retryAt.get(), reason, now)
				: this.reminders.markFailed(claimed.reminderId(), claimed.claimToken(),
						retryable ? "gave up after attempt " + claimed.attemptNo() + ", " + reason : reason, now);
		complete(claimed, outcome, null, reason, now);
		if (updated == 0) {
			stale(claimed, outcome);
			return;
		}
		this.deliveries.release(claimed.idempotencyKey(), claimed.attemptNo());
		logRetryOrFailure(claimed, outcome, reason, retryAt);
	}

	/**
	 * The provider may have delivered it (timeout, reset, 5xx), so the delivery record is kept: the
	 * retry is suppressed and finishes the reminder as SENT without resending. If no retry is left,
	 * it ends FAILED with the record in place, i.e. "in doubt", not "never sent".
	 */
	private void mayHaveBeenSent(ClaimedReminder claimed, String cause, Instant now) {
		String reason = "outcome unknown: " + cause;
		Optional<Instant> retryAt = nextRetryAt(claimed, now);
		int updated = retryAt.isPresent()
				? this.reminders.releaseForRetry(claimed.reminderId(), claimed.claimToken(), retryAt.get(),
						reason + "; the retry will not resend", now)
				: this.reminders.markFailed(claimed.reminderId(), claimed.claimToken(),
						"in doubt after attempt " + claimed.attemptNo() + ", " + reason, now);
		complete(claimed, AttemptOutcome.UNKNOWN, null, reason, now);
		if (updated == 0) {
			stale(claimed, AttemptOutcome.UNKNOWN);
			return;
		}
		logRetryOrFailure(claimed, AttemptOutcome.UNKNOWN, reason, retryAt);
	}

	private Optional<Instant> nextRetryAt(ClaimedReminder claimed, Instant now) {
		return this.policy.nextRetryAt(claimed.type(), claimed.dueAt(), claimed.attemptNo(), claimed.appointmentAt(),
				now);
	}

	private void complete(ClaimedReminder claimed, AttemptOutcome outcome, String providerMessageId, String note,
			Instant now) {
		this.attempts.complete(claimed.reminderId(), claimed.claimToken(), outcome.name(), providerMessageId, note, now);
	}

	private void stale(ClaimedReminder claimed, AttemptOutcome outcome) {
		AfterCommit.run(() -> log.warn(
				"REMINDER_STALE_OUTCOME reminderId={} key={} attempt={} outcome={}: lease expired and the reminder was reclaimed; its newer state was not overwritten",
				claimed.reminderId(), claimed.idempotencyKey(), claimed.attemptNo(), outcome));
	}

	private void logRetryOrFailure(ClaimedReminder claimed, AttemptOutcome outcome, String reason,
			Optional<Instant> retryAt) {
		AfterCommit.run(() -> {
			if (retryAt.isPresent()) {
				log.info("REMINDER_RETRY_SCHEDULED reminderId={} key={} attempt={} outcome={} retryAt={} reason={}",
						claimed.reminderId(), claimed.idempotencyKey(), claimed.attemptNo(), outcome, retryAt.get(), reason);
			}
			else {
				log.warn("REMINDER_FAILED reminderId={} key={} attempt={} outcome={} reason={}", claimed.reminderId(),
						claimed.idempotencyKey(), claimed.attemptNo(), outcome, reason);
			}
		});
	}

	private static String describe(SendResult.Suppressed suppressed) {
		return (suppressed.providerMessageId() != null)
				? "already accepted by the provider on attempt " + suppressed.handedOverByAttempt() + "; not resent"
				: "already handed to the provider on attempt " + suppressed.handedOverByAttempt()
						+ " (no answer recorded); not resent";
	}

	private static void pause(Duration duration) {
		try {
			Thread.sleep(duration);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

}
