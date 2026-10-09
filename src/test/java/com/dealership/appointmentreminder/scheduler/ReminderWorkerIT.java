package com.dealership.appointmentreminder.scheduler;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.dealership.appointmentreminder.dto.AppointmentResponse;
import com.dealership.appointmentreminder.entity.Reminder;
import com.dealership.appointmentreminder.service.ClaimedReminder;
import com.dealership.appointmentreminder.service.ReminderDeliveryService;
import com.dealership.appointmentreminder.support.IntegrationTest;
import com.dealership.appointmentreminder.support.RecordingNotificationSender.Call;

import static com.dealership.appointmentreminder.entity.ReminderStatus.CANCELLED;
import static com.dealership.appointmentreminder.entity.ReminderStatus.EXPIRED;
import static com.dealership.appointmentreminder.entity.ReminderStatus.FAILED;
import static com.dealership.appointmentreminder.entity.ReminderStatus.PENDING;
import static com.dealership.appointmentreminder.entity.ReminderStatus.PROCESSING;
import static com.dealership.appointmentreminder.entity.ReminderStatus.SENT;
import static com.dealership.appointmentreminder.entity.ReminderStatus.SUPERSEDED;
import static com.dealership.appointmentreminder.entity.ReminderType.REMINDER_24H;
import static com.dealership.appointmentreminder.entity.ReminderType.REMINDER_2H;
import static org.assertj.core.api.Assertions.assertThat;

/** The normal life of a reminder: when it is sent, how each provider answer is handled, and when it gives up. */
class ReminderWorkerIT extends IntegrationTest {

	@Autowired
	private ReminderDeliveryService deliveryService;

	@Test
	void sendsEachReminderOnceAtItsDueTimeAndNotBefore() {
		AppointmentResponse booked = book(Duration.ofDays(3));
		Reminder dayBefore = reminder(booked, REMINDER_24H);
		Reminder twoHoursBefore = reminder(booked, REMINDER_2H);
		assertThat(dayBefore.getStatus()).isEqualTo(PENDING);
		assertThat(dayBefore.getDueAt()).isEqualTo(booked.scheduledAt().minus(Duration.ofHours(24)));

		this.clock.setInstant(dayBefore.getDueAt().minusMillis(1));
		assertThat(this.worker.runOnce()).isZero();
		assertThat(this.sender.calls()).as("nothing before the due time").isEmpty();

		this.clock.setInstant(dayBefore.getDueAt());
		this.worker.runOnce();
		Reminder sent = reload(dayBefore);
		assertThat(sent.getStatus()).isEqualTo(SENT);
		assertThat(sent.getSentAt()).isEqualTo(dayBefore.getDueAt());
		assertThat(sent.getAttemptCount()).isEqualTo(1);
		assertThat(sent.getClaimToken()).isNull();
		assertThat(sent.getProcessingUntil()).isNull();
		assertThat(attemptOutcomes(dayBefore)).containsExactly("ACCEPTED");
		assertThat(deliveryOf(dayBefore)).hasValueSatisfying((row) -> {
			assertThat(row.get("status")).isEqualTo("ACCEPTED");
			assertThat(row.get("attempt_no")).isEqualTo(1);
			assertThat(row.get("provider_message_id")).isEqualTo(sent.getProviderMessageId());
		});
		assertThat(this.sender.calls().get(0).notification().body())
			.contains("Oil change", "2021 Honda Civic", "Fri, Oct 9, 2026 at 9:00 AM UTC");

		this.worker.runOnce();
		assertThat(this.sender.calls()).as("running again changes nothing").hasSize(1);

		this.clock.setInstant(twoHoursBefore.getDueAt().plusSeconds(3));
		this.worker.runOnce();
		assertThat(reload(twoHoursBefore).getStatus()).isEqualTo(SENT);
		assertThat(this.sender.calls()).extracting(Call::idempotencyKey)
			.containsExactly(dayBefore.getIdempotencyKey(), twoHoursBefore.getIdempotencyKey());
	}

	@Test
	void aLateReminderInsideItsWindowIsStillSent() {
		Reminder dayBefore = reminder(book(Duration.ofDays(3)), REMINDER_24H);
		this.clock.setInstant(dayBefore.getDueAt().plus(Duration.ofHours(6))); // the last sendable instant

		this.worker.runOnce();

		assertThat(reload(dayBefore).getStatus()).isEqualTo(SENT);
	}

	@Test
	void aReminderFoundAfterItsWindowExpiresInsteadOfBeingSent() {
		Reminder dayBefore = reminder(book(Duration.ofDays(3)), REMINDER_24H);
		this.clock.setInstant(dayBefore.getDueAt().plus(Duration.ofHours(6)).plusMillis(1)); // e.g. after an outage

		this.worker.runOnce();

		assertThat(reload(dayBefore).getStatus()).isEqualTo(EXPIRED);
		assertThat(reload(dayBefore).getAttemptCount()).isZero();
		assertThat(this.sender.calls()).isEmpty();
	}

	@Test
	void aRetryableRejectionIsRetriedWithBackoffUntilAccepted() {
		Reminder dayBefore = reminder(book(Duration.ofDays(3)), REMINDER_24H);
		this.sender.thenRetryableRejection("429 Too Many Requests").thenRetryableRejection("429 Too Many Requests");

		this.clock.setInstant(dayBefore.getDueAt());
		this.worker.runOnce();
		Reminder afterFirstTry = reload(dayBefore);
		assertThat(afterFirstTry.getStatus()).isEqualTo(PENDING);
		assertThat(afterFirstTry.getNextAttemptAt()).isAfter(this.clock.instant());
		assertThat(afterFirstTry.getStatusReason()).isEqualTo("rejected: 429 Too Many Requests");
		assertThat(deliveryOf(dayBefore)).as("a definite rejection frees the key, so the retry really sends").isEmpty();

		this.worker.runOnce();
		assertThat(this.sender.calls()).as("backoff not elapsed yet").hasSize(1);

		this.clock.setInstant(afterFirstTry.getNextAttemptAt());
		this.worker.runOnce();
		this.clock.setInstant(reload(dayBefore).getNextAttemptAt());
		this.worker.runOnce();

		Reminder done = reload(dayBefore);
		assertThat(done.getStatus()).isEqualTo(SENT);
		assertThat(done.getAttemptCount()).isEqualTo(3);
		assertThat(attemptOutcomes(dayBefore)).containsExactly("RETRYABLE", "RETRYABLE", "ACCEPTED");
		assertThat(deliveryOf(dayBefore)).hasValueSatisfying((row) -> assertThat(row.get("attempt_no")).isEqualTo(3));
		assertThat(this.sender.possibleDeliveriesOf(dayBefore.getIdempotencyKey())).isEqualTo(1);
	}

	@Test
	void givesUpAfterFiveAttempts() {
		Reminder dayBefore = reminder(book(Duration.ofDays(3)), REMINDER_24H);
		for (int i = 0; i < 5; i++) {
			this.sender.thenRetryableRejection("429 Too Many Requests");
		}
		for (int i = 0; i < 5; i++) {
			this.clock.setInstant(reload(dayBefore).getNextAttemptAt());
			this.worker.runOnce();
		}

		Reminder failed = reload(dayBefore);
		assertThat(failed.getStatus()).isEqualTo(FAILED);
		assertThat(failed.getAttemptCount()).isEqualTo(5);
		assertThat(failed.getStatusReason()).isEqualTo("gave up after attempt 5, rejected: 429 Too Many Requests");
		assertThat(attemptOutcomes(dayBefore)).hasSize(5).containsOnly("RETRYABLE");
		assertThat(this.sender.totalPossibleDeliveries()).isZero();
		assertThat(deliveryOf(dayBefore)).as("never accepted, so nothing is recorded as handed over").isEmpty();

		this.clock.advance(Duration.ofHours(1));
		this.worker.runOnce();
		assertThat(this.sender.calls()).as("FAILED is terminal").hasSize(5);
	}

	@Test
	void aPermanentRejectionFailsWithoutRetrying() {
		Reminder dayBefore = reminder(book(Duration.ofDays(3)), REMINDER_24H);
		this.sender.thenPermanentRejection("invalid phone number");

		this.clock.setInstant(dayBefore.getDueAt());
		this.worker.runOnce();
		this.clock.advance(Duration.ofHours(1));
		this.worker.runOnce();

		assertThat(reload(dayBefore).getStatus()).isEqualTo(FAILED);
		assertThat(reload(dayBefore).getStatusReason()).isEqualTo("rejected permanently: invalid phone number");
		assertThat(attemptOutcomes(dayBefore)).containsExactly("REJECTED");
		assertThat(this.sender.calls()).hasSize(1);
		assertThat(deliveryOf(dayBefore)).isEmpty();
	}

	@Test
	void anAmbiguousFailureIsRetriedButTheRetryNeverResends() {
		Reminder dayBefore = reminder(book(Duration.ofDays(3)), REMINDER_24H);
		this.sender.thenThrowAfterDelivery(new IllegalStateException("Read timed out"));

		this.clock.setInstant(dayBefore.getDueAt());
		this.worker.runOnce();
		Reminder afterTimeout = reload(dayBefore);
		assertThat(afterTimeout.getStatus()).isEqualTo(PENDING);
		assertThat(afterTimeout.getStatusReason()).contains("outcome unknown", "Read timed out");
		assertThat(deliveryOf(dayBefore)).as("the key stays taken: the provider may have delivered it")
			.hasValueSatisfying((row) -> assertThat(row.get("status")).isEqualTo("RESERVED"));

		this.clock.setInstant(afterTimeout.getNextAttemptAt());
		this.worker.runOnce();

		Reminder done = reload(dayBefore);
		assertThat(done.getStatus()).isEqualTo(SENT);
		assertThat(done.getStatusReason())
			.isEqualTo("already handed to the provider on attempt 1 (no answer recorded); not resent");
		assertThat(attemptOutcomes(dayBefore)).containsExactly("UNKNOWN", "SUPPRESSED");
		assertThat(this.sender.calls()).hasSize(1);
	}

	@Test
	void anUnknownOutcomeOnTheLastAttemptEndsFailedButInDoubt() {
		Reminder dayBefore = reminder(book(Duration.ofDays(3)), REMINDER_24H);
		for (int i = 0; i < 4; i++) {
			this.sender.thenRetryableRejection("429 Too Many Requests");
		}
		this.sender.thenUnknown("502 Bad Gateway");
		for (int i = 0; i < 5; i++) {
			this.clock.setInstant(reload(dayBefore).getNextAttemptAt());
			this.worker.runOnce();
		}

		Reminder failed = reload(dayBefore);
		assertThat(failed.getStatus()).isEqualTo(FAILED);
		assertThat(failed.getStatusReason()).isEqualTo("in doubt after attempt 5, outcome unknown: 502 Bad Gateway");
		assertThat(deliveryOf(dayBefore)).as("recorded as handed over, so it shows up for reconciliation")
			.hasValueSatisfying((row) -> assertThat(row.get("status")).isEqualTo("RESERVED"));
		assertThat(this.sender.possibleDeliveriesOf(dayBefore.getIdempotencyKey())).isEqualTo(1);
	}

	@Test
	void aCancelledAppointmentIsNeverReminded() {
		AppointmentResponse booked = book(Duration.ofDays(3));
		this.appointmentService.cancel(booked.id());

		this.clock.setInstant(reminder(booked, REMINDER_24H).getDueAt());
		this.worker.runOnce();
		this.clock.setInstant(reminder(booked, REMINDER_2H).getDueAt());
		this.worker.runOnce();

		assertThat(this.sender.calls()).isEmpty();
		assertThat(reminder(booked, REMINDER_24H).getStatus()).isEqualTo(CANCELLED);
		assertThat(reminder(booked, REMINDER_2H).getStatus()).isEqualTo(CANCELLED);
	}

	@Test
	void aRescheduledAppointmentIsRemindedForTheNewTimeOnly() {
		AppointmentResponse booked = book(Duration.ofDays(3));
		Reminder oldDayBefore = reminder(booked, REMINDER_24H);

		this.appointmentService.reschedule(booked.id(), T0.plus(Duration.ofDays(5)));

		assertThat(reload(oldDayBefore).getStatus()).isEqualTo(SUPERSEDED);
		Reminder newDayBefore = reminderOf(booked.id(), REMINDER_24H);
		assertThat(newDayBefore.getAppointmentRevision()).isEqualTo(2);
		assertThat(newDayBefore.getIdempotencyKey()).isEqualTo("appt-" + booked.id() + "-r2-REMINDER_24H");

		this.clock.setInstant(oldDayBefore.getDueAt());
		this.worker.runOnce();
		assertThat(this.sender.calls()).as("nothing for the old time").isEmpty();

		this.clock.setInstant(newDayBefore.getDueAt());
		this.worker.runOnce();
		assertThat(this.sender.calls()).extracting(Call::idempotencyKey).containsExactly(newDayBefore.getIdempotencyKey());
		assertThat(reload(newDayBefore).getStatus()).isEqualTo(SENT);
	}

	@Test
	void aReminderInFlightDuringACancelIsNotRetriedAfterIt() {
		AppointmentResponse booked = book(Duration.ofDays(3));
		Reminder dayBefore = reminder(booked, REMINDER_24H);
		this.clock.setInstant(dayBefore.getDueAt());
		ClaimedReminder inFlight = this.worker.claimDueBatch().claimed().getFirst();

		this.appointmentService.cancel(booked.id());
		assertThat(reload(dayBefore).getStatus()).as("in flight, so the cancel can't touch it").isEqualTo(PROCESSING);

		this.sender.thenRetryableRejection("429 Too Many Requests");
		this.deliveryService.deliver(inFlight);
		this.clock.setInstant(reload(dayBefore).getNextAttemptAt());
		this.worker.runOnce();

		assertThat(reload(dayBefore).getStatus()).isEqualTo(CANCELLED);
		assertThat(this.sender.totalPossibleDeliveries()).isZero();
	}

	@Test
	void aReminderInFlightDuringARescheduleIsSupersededInsteadOfRetried() {
		AppointmentResponse booked = book(Duration.ofDays(3));
		Reminder oldDayBefore = reminder(booked, REMINDER_24H);
		this.clock.setInstant(oldDayBefore.getDueAt());
		ClaimedReminder inFlight = this.worker.claimDueBatch().claimed().getFirst();

		this.appointmentService.reschedule(booked.id(), T0.plus(Duration.ofDays(5)));
		this.sender.thenRetryableRejection("429 Too Many Requests");
		this.deliveryService.deliver(inFlight);
		this.clock.setInstant(reload(oldDayBefore).getNextAttemptAt());
		this.worker.runOnce();

		assertThat(reload(oldDayBefore).getStatus()).isEqualTo(SUPERSEDED);
		Reminder newDayBefore = reminderOf(booked.id(), REMINDER_24H);
		this.clock.setInstant(newDayBefore.getDueAt());
		this.worker.runOnce();
		assertThat(reload(newDayBefore).getStatus()).isEqualTo(SENT);
		assertThat(this.sender.possibleDeliveriesOf(oldDayBefore.getIdempotencyKey())).isZero();
		assertThat(this.sender.possibleDeliveriesOf(newDayBefore.getIdempotencyKey())).isEqualTo(1);
	}

	@Test
	void aTickDrainsMoreThanOneBatch() {
		bookDayBeforeRemindersAllDueNow(12); // the test batch size is 5

		this.worker.tick();

		assertThat(this.sender.calls()).hasSize(12);
		assertThat(this.jdbc.queryForObject("SELECT COUNT(*) FROM reminder WHERE status = 'SENT'", Integer.class))
			.isEqualTo(12);
	}

	@Test
	void aReminderThatCannotBeRenderedFailsAloneWithoutStoppingTheBatch() {
		Reminder broken = reminder(book(Duration.ofDays(3)), REMINDER_24H);
		Reminder healthy = reminder(book(Duration.ofDays(3).plusMinutes(1)), REMINDER_24H);
		// Bad data written by another system: SMS is still preferred, but the phone number is gone.
		this.jdbc.update("UPDATE appointment SET customer_phone = NULL, customer_email = 'alex@example.com' WHERE id = ?",
				broken.getAppointmentId());
		this.clock.setInstant(healthy.getDueAt());

		this.worker.runOnce();

		assertThat(reload(broken).getStatus()).isEqualTo(FAILED);
		assertThat(reload(broken).getStatusReason())
			.isEqualTo("could not render the message: appointment has no SMS contact");
		assertThat(reload(healthy).getStatus()).isEqualTo(SENT);
	}

}
