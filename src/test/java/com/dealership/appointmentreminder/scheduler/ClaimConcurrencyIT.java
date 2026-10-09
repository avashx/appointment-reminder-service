package com.dealership.appointmentreminder.scheduler;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.transaction.support.TransactionTemplate;

import com.dealership.appointmentreminder.entity.Reminder;
import com.dealership.appointmentreminder.scheduler.ReminderWorker.ClaimBatch;
import com.dealership.appointmentreminder.service.ClaimedReminder;
import com.dealership.appointmentreminder.support.IntegrationTest;

import static com.dealership.appointmentreminder.entity.ReminderStatus.PENDING;
import static com.dealership.appointmentreminder.entity.ReminderStatus.PROCESSING;
import static com.dealership.appointmentreminder.entity.ReminderType.REMINDER_24H;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Step 1 and Step 2 under contention, against real InnoDB locking: SKIP LOCKED hands concurrent
 * claimers disjoint rows without blocking, only due PENDING rows are claimed, and an expired lease
 * is reclaimed exactly once.
 */
class ClaimConcurrencyIT extends IntegrationTest {

	@Autowired
	private TransactionTemplate transactions;

	@Test
	void aRowLockedByAnotherWorkerIsSkippedInsteadOfWaitedFor() throws Exception {
		List<Reminder> due = bookDayBeforeRemindersAllDueNow(2);
		CountDownLatch locked = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		// Worker A's claim transaction has locked the oldest due row and is still open.
		CompletableFuture<List<Long>> workerA = CompletableFuture.supplyAsync(() -> this.transactions.execute((status) -> {
			List<Long> ids = this.reminderRepository.lockDueBatch(this.clock.instant(), 1)
				.stream()
				.map(Reminder::getId)
				.toList();
			locked.countDown();
			awaitQuietly(release);
			status.setRollbackOnly();
			return ids;
		}));
		assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

		try {
			// A plain FOR UPDATE on that row would wait for A (here: until a 1 second lock timeout)...
			assertThatThrownBy(() -> lockWithoutSkipLocked(due.get(0).getId()))
				.isInstanceOf(PessimisticLockingFailureException.class);

			// ...while worker B's SKIP LOCKED claim returns at once, with the row A doesn't hold.
			long started = System.nanoTime();
			ClaimBatch batch = this.worker.claimDueBatch();
			Duration took = Duration.ofNanos(System.nanoTime() - started);

			assertThat(batch.claimed()).extracting(ClaimedReminder::reminderId).containsExactly(due.get(1).getId());
			assertThat(took).isLessThan(Duration.ofSeconds(2));
		}
		finally {
			release.countDown();
		}
		assertThat(workerA.get(10, TimeUnit.SECONDS)).containsExactly(due.get(0).getId());
		assertThat(reload(due.get(0)).getStatus()).as("A rolled back, so its row is still claimable").isEqualTo(PENDING);
	}

	@RepeatedTest(3)
	void concurrentClaimersGetDisjointBatches() throws Exception {
		bookDayBeforeRemindersAllDueNow(40);
		List<Long> claimed = Collections.synchronizedList(new ArrayList<>());

		runConcurrently(8, (thread) -> {
			ClaimBatch batch = this.worker.claimDueBatch();
			assertThat(batch.claimed()).hasSizeLessThanOrEqualTo(this.properties.worker().batchSize());
			batch.claimed().forEach((claim) -> claimed.add(claim.reminderId()));
		});

		assertThat(claimed).as("no row claimed twice").doesNotHaveDuplicates();
		assertThat(claimed).as("8 claimers x batch of 5").hasSize(40);
		assertThat(this.jdbc.queryForObject("SELECT COUNT(*) FROM notification_attempt", Integer.class)).isEqualTo(40);
	}

	@Test
	void aClaimCommitsProcessingWithALeaseBeforeAnythingIsSent() {
		Reminder dayBefore = reminder(book(Duration.ofDays(3)), REMINDER_24H);
		this.clock.setInstant(dayBefore.getDueAt());

		ClaimedReminder claim = this.worker.claimDueBatch().claimed().getFirst();

		Reminder row = reload(dayBefore);
		assertThat(row.getStatus()).isEqualTo(PROCESSING);
		assertThat(row.getClaimToken()).isEqualTo(claim.claimToken());
		assertThat(row.getProcessingUntil()).isEqualTo(dayBefore.getDueAt().plus(Duration.ofMinutes(2)));
		assertThat(row.getAttemptCount()).isEqualTo(1);
		assertThat(attemptOutcomes(dayBefore)).containsExactly("STARTED");
		assertThat(this.jdbc.queryForObject("SELECT payload FROM notification_attempt WHERE reminder_id = ?",
				String.class, dayBefore.getId()))
			.as("the exact payload is stored before the send").contains(claim.notification().body());
		assertThat(this.sender.calls()).as("claiming sends nothing").isEmpty();
	}

	@Test
	void onlyDuePendingRowsAreClaimed() {
		Reminder cancelled = reminder(book(Duration.ofDays(3).minusMinutes(3)), REMINDER_24H);
		Reminder alreadySent = reminder(book(Duration.ofDays(3).minusMinutes(2)), REMINDER_24H);
		Reminder inFlight = reminder(book(Duration.ofDays(3).minusMinutes(1)), REMINDER_24H);
		Reminder due = reminder(book(Duration.ofDays(3)), REMINDER_24H);
		Reminder notYetDue = reminder(book(Duration.ofDays(4)), REMINDER_24H);
		this.appointmentService.cancel(cancelled.getAppointmentId());
		this.clock.setInstant(alreadySent.getDueAt());
		this.worker.runOnce();
		this.clock.setInstant(inFlight.getDueAt());
		this.worker.claimDueBatch();
		this.clock.setInstant(due.getDueAt());

		ClaimBatch batch = this.worker.claimDueBatch();

		assertThat(batch.lockedRows()).isEqualTo(1);
		assertThat(batch.claimed()).extracting(ClaimedReminder::reminderId).containsExactly(due.getId());
		assertThat(reload(inFlight).getAttemptCount()).as("its live lease protects it").isEqualTo(1);
		assertThat(reload(notYetDue).getStatus()).isEqualTo(PENDING);
	}

	@Test
	void aClaimTakesAtMostOneBatchOldestFirst() {
		List<Reminder> due = bookDayBeforeRemindersAllDueNow(12);

		ClaimBatch batch = this.worker.claimDueBatch();

		assertThat(batch.lockedRows()).isEqualTo(5);
		assertThat(batch.claimed()).extracting(ClaimedReminder::reminderId)
			.containsExactlyElementsOf(due.subList(0, 5).stream().map(Reminder::getId).toList());
	}

	@Test
	void aLiveLeaseIsNeverReclaimed() {
		Reminder dayBefore = reminder(book(Duration.ofDays(3)), REMINDER_24H);
		this.clock.setInstant(dayBefore.getDueAt());
		this.worker.claimDueBatch();

		this.clock.advance(Duration.ofMinutes(1)); // the lease is 2 minutes

		assertThat(this.worker.reclaimExpiredLeases()).isZero();
		assertThat(this.worker.runOnce()).as("not claimable either").isZero();
		assertThat(reload(dayBefore).getStatus()).isEqualTo(PROCESSING);
	}

	@RepeatedTest(3)
	void anExpiredLeaseIsReclaimedExactlyOnceWhenInstancesRace() throws Exception {
		Reminder dayBefore = reminder(book(Duration.ofDays(3)), REMINDER_24H);
		this.clock.setInstant(dayBefore.getDueAt());
		this.worker.claimDueBatch(); // and then the worker dies
		waitOutTheLease();
		AtomicInteger reclaimed = new AtomicInteger();

		runConcurrently(8, (thread) -> reclaimed.addAndGet(this.worker.reclaimExpiredLeases()));

		assertThat(reclaimed).hasValue(1);
		Reminder row = reload(dayBefore);
		assertThat(row.getStatus()).isEqualTo(PENDING);
		assertThat(row.getClaimToken()).isNull();
		assertThat(row.getProcessingUntil()).isNull();
		assertThat(row.getNextAttemptAt()).isEqualTo(this.clock.instant());
		assertThat(attemptOutcomes(dayBefore)).containsExactly("ABANDONED");
	}

	/** The same row lock without SKIP LOCKED: waits for the holder, here at most 1 second. */
	private void lockWithoutSkipLocked(long reminderId) {
		this.transactions.executeWithoutResult((status) -> {
			this.jdbc.execute("SET SESSION innodb_lock_wait_timeout = 1");
			try {
				this.jdbc.queryForList("SELECT id FROM reminder WHERE id = ? FOR UPDATE", reminderId);
			}
			finally {
				this.jdbc.execute("SET SESSION innodb_lock_wait_timeout = DEFAULT");
			}
		});
	}

	private static void awaitQuietly(CountDownLatch latch) {
		try {
			latch.await(30, TimeUnit.SECONDS);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

}
