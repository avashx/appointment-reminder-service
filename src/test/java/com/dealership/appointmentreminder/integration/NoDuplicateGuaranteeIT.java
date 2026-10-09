package com.dealership.appointmentreminder.integration;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;

import com.dealership.appointmentreminder.dto.AppointmentResponse;
import com.dealership.appointmentreminder.dto.Notification;
import com.dealership.appointmentreminder.entity.Channel;
import com.dealership.appointmentreminder.entity.Reminder;
import com.dealership.appointmentreminder.entity.ReminderStatus;
import com.dealership.appointmentreminder.service.ClaimedReminder;
import com.dealership.appointmentreminder.service.IdempotentNotificationSender;
import com.dealership.appointmentreminder.service.ReminderDeliveryService;
import com.dealership.appointmentreminder.service.SendResult;
import com.dealership.appointmentreminder.support.IntegrationTest;

import static com.dealership.appointmentreminder.entity.ReminderStatus.CANCELLED;
import static com.dealership.appointmentreminder.entity.ReminderStatus.FAILED;
import static com.dealership.appointmentreminder.entity.ReminderStatus.PROCESSING;
import static com.dealership.appointmentreminder.entity.ReminderStatus.SENT;
import static com.dealership.appointmentreminder.entity.ReminderType.REMINDER_24H;
import static com.dealership.appointmentreminder.entity.ReminderType.REMINDER_2H;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The proof of "a customer never receives the same reminder twice": every way a duplicate could
 * happen, forced on purpose. Crashes on either side of the hand-off, a worker that outlives its
 * lease, racing workers and instances, a cancel racing the claim, and each protection layer on
 * its own. On top of each test's own assertions, the base class checks the invariants after
 * every test, including the provider's view: no key was possibly delivered twice.
 */
class NoDuplicateGuaranteeIT extends IntegrationTest {

	@Autowired
	private IdempotentNotificationSender guard;

	@Autowired
	private ReminderDeliveryService deliveryService;

	// ---- crash and reclaim ----

	@Test
	void aCrashAfterTheProviderAcceptedIsReclaimedAndSuppressedNotResent() {
		Reminder reminder = reminder(book(Duration.ofDays(3)), REMINDER_24H);
		this.sender.thenCrashAfterDelivery();
		this.clock.setInstant(reminder.getDueAt());

		runOnceSurvivingCrash();
		assertThat(reload(reminder).getStatus()).as("the outcome was never recorded").isEqualTo(PROCESSING);
		assertThat(attemptOutcomes(reminder)).containsExactly("STARTED");
		assertThat(deliveryOf(reminder)).as("but the hand-off was")
			.hasValueSatisfying((row) -> assertThat(row.get("status")).isEqualTo("RESERVED"));

		// After the restart the workers keep polling, but the lease still protects the row.
		this.clock.advance(Duration.ofMinutes(1));
		assertThat(this.worker.runOnce()).isZero();
		assertThat(reload(reminder).getStatus()).isEqualTo(PROCESSING);

		// The lease runs out: Step 1 reclaims, Step 2 claims attempt 2, Step 3 finds the key and sends nothing.
		this.clock.advance(Duration.ofMinutes(1).plusSeconds(1));
		this.worker.runOnce();

		Reminder done = reload(reminder);
		assertThat(done.getStatus()).isEqualTo(SENT);
		assertThat(done.getAttemptCount()).isEqualTo(2);
		assertThat(attemptOutcomes(reminder)).containsExactly("ABANDONED", "SUPPRESSED");
		assertThat(this.sender.possibleDeliveriesOf(reminder.getIdempotencyKey())).isEqualTo(1);
	}

	@Test
	void aCrashRightAfterTheClaimIsReclaimedAndSentExactlyOnce() {
		Reminder reminder = reminder(book(Duration.ofDays(3)), REMINDER_24H);
		this.clock.setInstant(reminder.getDueAt());

		this.worker.claimDueBatch(); // TX1 committed; the process dies before Step 3
		assertThat(deliveryOf(reminder)).isEmpty();
		waitOutTheLease();
		this.worker.runOnce();

		assertThat(reload(reminder).getStatus()).isEqualTo(SENT);
		assertThat(attemptOutcomes(reminder)).containsExactly("ABANDONED", "ACCEPTED");
		assertThat(this.sender.possibleDeliveriesOf(reminder.getIdempotencyKey())).isEqualTo(1);
	}

	@Test
	void aCrashBetweenTheRecordAndTheProviderCallCanMissAReminderButNeverDuplicateIt() {
		// The one gap of record-then-send, on purpose: the key was recorded, the request never left.
		Reminder reminder = reminder(book(Duration.ofDays(3)), REMINDER_24H);
		this.sender.thenCrashBeforeDelivery();
		this.clock.setInstant(reminder.getDueAt());

		runOnceSurvivingCrash();
		waitOutTheLease();
		this.worker.runOnce();

		assertThat(reload(reminder).getStatus()).isEqualTo(SENT);
		assertThat(reload(reminder).getStatusReason()).contains("no answer recorded");
		assertThat(this.sender.possibleDeliveriesOf(reminder.getIdempotencyKey())).as("a miss, never a duplicate")
			.isZero();
		assertThat(reconciliationList()).as("and it is flagged: handed over, never acknowledged")
			.containsExactly(reminder.getIdempotencyKey());
	}

	@Test
	void aWorkerThatOutlivesItsLeaseIsFencedAndItsReplacementDoesNotResend() throws Exception {
		Reminder reminder = reminder(book(Duration.ofDays(3)), REMINDER_24H);
		CountDownLatch insideProviderCall = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		this.sender.thenBlockThenAccept(insideProviderCall, release);
		this.clock.setInstant(reminder.getDueAt());

		CompletableFuture<Integer> slowWorker = CompletableFuture.supplyAsync(this.worker::runOnce);
		assertThat(insideProviderCall.await(10, TimeUnit.SECONDS)).isTrue();

		// Worker A is stuck in the provider call (GC pause, slow network) and its lease runs out.
		waitOutTheLease();
		this.worker.runOnce();
		assertThat(reload(reminder).getStatus()).isEqualTo(SENT);
		assertThat(attemptOutcomes(reminder)).containsExactly("ABANDONED", "SUPPRESSED");

		// A finally hears back. Its write to the reminder is fenced off; only its own attempt is updated.
		release.countDown();
		slowWorker.get(10, TimeUnit.SECONDS);

		Reminder done = reload(reminder);
		assertThat(done.getStatus()).isEqualTo(SENT);
		assertThat(done.getAttemptCount()).isEqualTo(2);
		assertThat(attemptOutcomes(reminder)).as("the audit says what really happened")
			.containsExactly("ACCEPTED", "SUPPRESSED");
		assertThat(deliveryOf(reminder)).hasValueSatisfying((row) -> {
			assertThat(row.get("status")).isEqualTo("ACCEPTED");
			assertThat(row.get("attempt_no")).isEqualTo(1);
		});
		assertThat(this.sender.possibleDeliveriesOf(reminder.getIdempotencyKey())).isEqualTo(1);
	}

	@Test
	void aReminderThatKeepsCrashingItsWorkerIsCappedAtFiveAttempts() {
		Reminder reminder = reminder(book(Duration.ofDays(3)), REMINDER_24H);
		this.clock.setInstant(reminder.getDueAt());

		for (int attempt = 1; attempt <= 5; attempt++) {
			this.worker.reclaimExpiredLeases();
			assertThat(this.worker.claimDueBatch().claimed()).as("attempt %d is claimed, then the process dies", attempt)
				.hasSize(1);
			waitOutTheLease();
		}
		this.worker.runOnce();

		Reminder failed = reload(reminder);
		assertThat(failed.getStatus()).isEqualTo(FAILED);
		assertThat(failed.getAttemptCount()).isEqualTo(5);
		assertThat(failed.getStatusReason())
			.isEqualTo("gave up after 5 attempts; the last one never reported an outcome");
		assertThat(attemptOutcomes(reminder)).hasSize(5).containsOnly("ABANDONED");
		assertThat(this.sender.calls()).isEmpty();
	}

	// ---- racing workers and instances ----

	@RepeatedTest(5)
	void manyWorkersRacingOverTheSameRemindersSendEachExactlyOnce() throws Exception {
		bookDayBeforeRemindersAllDueNow(40);

		// 8 threads, think 8 instances, each running dispatch cycles until nothing is left to claim.
		runConcurrently(8, (thread) -> {
			while (this.worker.runOnce() > 0) {
				// keep draining
			}
		});

		assertThat(this.sender.calls()).hasSize(40);
		assertThat(this.jdbc.queryForObject("SELECT COUNT(*) FROM reminder WHERE status = 'SENT'", Integer.class))
			.isEqualTo(40);
		assertThat(this.jdbc.queryForObject("SELECT COUNT(*) FROM notification_attempt", Integer.class))
			.as("no row was ever claimed twice").isEqualTo(40);
	}

	@RepeatedTest(10)
	void aCancelRacingTheClaimEitherWinsOrLosesButNeverDuplicates() throws Exception {
		AppointmentResponse booked = book(Duration.ofDays(3));
		Reminder reminder = reminder(booked, REMINDER_24H);
		this.clock.setInstant(reminder.getDueAt());

		runConcurrently(2, (thread) -> {
			if (thread == 0) {
				this.worker.runOnce();
			}
			else {
				this.appointmentService.cancel(booked.id());
			}
		});
		this.worker.runOnce(); // if the cancel won, nothing is sent; if the claim won, nothing changes

		ReminderStatus outcome = reload(reminder).getStatus();
		assertThat(outcome).isIn(SENT, CANCELLED);
		assertThat(this.sender.possibleDeliveriesOf(reminder.getIdempotencyKey())).isEqualTo((outcome == SENT) ? 1 : 0);
		assertThat(reminder(booked, REMINDER_2H).getStatus()).isEqualTo(CANCELLED);
	}

	// ---- each protection layer on its own ----

	@Test
	void schedulingProtectionTheDatabaseRefusesASecondRowForTheSameReminder() {
		Reminder existing = reminder(book(Duration.ofDays(3)), REMINDER_24H);

		assertThatThrownBy(() -> insertReminder(existing.getAppointmentId(), 1, "REMINDER_24H", "some-other-key"))
			.as("uk_reminder_logical").isInstanceOf(DuplicateKeyException.class);
		assertThatThrownBy(() -> insertReminder(existing.getAppointmentId(), 2, "REMINDER_24H", existing.getIdempotencyKey()))
			.as("uk_reminder_idempotency").isInstanceOf(DuplicateKeyException.class);
	}

	@Test
	void sendingProtectionARecordedKeyIsNeverHandedToTheProviderAgain() {
		Reminder reminder = reminder(book(Duration.ofDays(3)), REMINDER_24H);
		// Recorded by an earlier process that died before it could mark the reminder SENT.
		this.jdbc.update("""
				INSERT INTO notification_delivery
				       (idempotency_key, reminder_id, attempt_no, channel, status, provider_message_id, recorded_at, accepted_at)
				VALUES (?, ?, 1, 'SMS', 'ACCEPTED', 'earlier-process', ?, ?)""", reminder.getIdempotencyKey(),
				reminder.getId(), utc(T0), utc(T0));
		this.clock.setInstant(reminder.getDueAt());

		this.worker.runOnce();

		assertThat(this.sender.calls()).as("the provider was never called").isEmpty();
		Reminder done = reload(reminder);
		assertThat(done.getStatus()).isEqualTo(SENT);
		assertThat(done.getProviderMessageId()).isEqualTo("earlier-process");
		assertThat(attemptOutcomes(reminder)).containsExactly("SUPPRESSED");
	}

	@Test
	void sendingProtectionConcurrentHandOffsOfOneKeyReachTheProviderOnce() throws Exception {
		Reminder reminder = reminder(book(Duration.ofDays(3)), REMINDER_24H);
		Notification notification = new Notification(reminder.getIdempotencyKey(), reminder.getAppointmentId(),
				reminder.getId(), 1, 1L, REMINDER_24H, Channel.SMS, "+13125550142", "Hi Alex, a reminder");
		List<SendResult> results = Collections.synchronizedList(new ArrayList<>());

		runConcurrently(8, (thread) -> results.add(this.guard.send(notification)));

		assertThat(results).filteredOn(SendResult.Accepted.class::isInstance).hasSize(1);
		assertThat(results).filteredOn(SendResult.Suppressed.class::isInstance).hasSize(7);
		assertThat(this.sender.calls()).hasSize(1);
	}

	@Test
	void fencingAStaleClaimTokenCanNeitherSendNorOverwrite() {
		Reminder reminder = reminder(book(Duration.ofDays(3)), REMINDER_24H);
		this.clock.setInstant(reminder.getDueAt());
		ClaimedReminder stale = this.worker.claimDueBatch().claimed().getFirst();
		waitOutTheLease();
		this.worker.runOnce(); // reclaimed and sent by "another instance"
		assertThat(this.sender.calls()).hasSize(1);

		// The first worker wakes up: past its lease it does not even start a send...
		this.deliveryService.deliver(stale);
		assertThat(this.sender.calls()).hasSize(1);

		// ...and any write under its old token matches no row.
		Instant now = this.clock.instant();
		assertThat(this.reminderRepository.markFailed(reminder.getId(), stale.claimToken(), "late", now)).isZero();
		assertThat(this.reminderRepository.releaseForRetry(reminder.getId(), stale.claimToken(), now, "late", now)).isZero();
		assertThat(this.reminderRepository.markSent(reminder.getId(), stale.claimToken(), "late", null, now)).isZero();
		assertThat(this.reminderRepository.reclaim(reminder.getId(), stale.claimToken(), now)).isZero();
		assertThat(reload(reminder).getStatus()).isEqualTo(SENT);
	}

	/** Recorded as handed to the provider, but never acknowledged within a lease: what ops would check with the provider. */
	private List<String> reconciliationList() {
		Instant cutoff = this.clock.instant().minus(this.properties.worker().processingLease());
		return this.jdbc.queryForList(
				"SELECT idempotency_key FROM notification_delivery WHERE status = 'RESERVED' AND recorded_at < ?",
				String.class, utc(cutoff));
	}

	private void insertReminder(long appointmentId, int revision, String type, String idempotencyKey) {
		LocalDateTime now = utc(T0);
		this.jdbc.update("""
				INSERT INTO reminder (appointment_id, appointment_revision, reminder_type, due_at, next_attempt_at,
				                      status, idempotency_key, created_at, updated_at)
				VALUES (?, ?, ?, ?, ?, 'PENDING', ?, ?, ?)""", appointmentId, revision, type, now, now, idempotencyKey,
				now, now);
	}

}
