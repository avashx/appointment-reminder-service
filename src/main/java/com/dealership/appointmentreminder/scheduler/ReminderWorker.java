package com.dealership.appointmentreminder.scheduler;

import java.lang.management.ManagementFactory;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import com.dealership.appointmentreminder.config.ReminderProperties;
import com.dealership.appointmentreminder.dto.Notification;
import com.dealership.appointmentreminder.entity.Appointment;
import com.dealership.appointmentreminder.entity.AppointmentStatus;
import com.dealership.appointmentreminder.entity.NotificationAttempt;
import com.dealership.appointmentreminder.entity.Reminder;
import com.dealership.appointmentreminder.entity.ReminderStatus;
import com.dealership.appointmentreminder.repository.AppointmentRepository;
import com.dealership.appointmentreminder.repository.NotificationAttemptRepository;
import com.dealership.appointmentreminder.repository.ReminderRepository;
import com.dealership.appointmentreminder.service.AfterCommit;
import com.dealership.appointmentreminder.service.ClaimedReminder;
import com.dealership.appointmentreminder.service.MessageRenderer;
import com.dealership.appointmentreminder.service.ReminderDeliveryService;
import com.dealership.appointmentreminder.service.ReminderPolicy;

/**
 * The dispatch cycle, run on every instance: Step 1 reclaim expired leases, Step 2 claim a batch,
 * then Steps 3 and 4 for each claimed reminder on the send pool, and wait for them. Nothing is held
 * in memory between cycles; all state is in the reminder table, so a restart loses nothing.
 */
@Component
public class ReminderWorker {

	private static final Logger log = LoggerFactory.getLogger(ReminderWorker.class);

	/** A large backlog is drained in steps, so a tick always ends (shutdown waits for the running tick). */
	private static final int MAX_BATCHES_PER_TICK = 50;

	private final ReminderRepository reminders;

	private final AppointmentRepository appointments;

	private final NotificationAttemptRepository attempts;

	private final ReminderPolicy policy;

	private final MessageRenderer renderer;

	private final ReminderDeliveryService delivery;

	private final ReminderProperties properties;

	private final TransactionTemplate transactions;

	private final ThreadPoolTaskExecutor sendExecutor;

	private final JsonMapper jsonMapper;

	private final Clock clock;

	private final String instanceId = ManagementFactory.getRuntimeMXBean().getName();

	public ReminderWorker(ReminderRepository reminders, AppointmentRepository appointments,
			NotificationAttemptRepository attempts, ReminderPolicy policy, MessageRenderer renderer,
			ReminderDeliveryService delivery, ReminderProperties properties, TransactionTemplate transactions,
			@Qualifier("reminderSendExecutor") ThreadPoolTaskExecutor sendExecutor, JsonMapper jsonMapper,
			Clock clock) {
		this.reminders = reminders;
		this.appointments = appointments;
		this.attempts = attempts;
		this.policy = policy;
		this.renderer = renderer;
		this.delivery = delivery;
		this.properties = properties;
		this.transactions = transactions;
		this.sendExecutor = sendExecutor;
		this.jsonMapper = jsonMapper;
		this.clock = clock;
	}

	public record ClaimBatch(int lockedRows, List<ClaimedReminder> claimed) {
	}

	@Scheduled(fixedDelayString = "${reminder.worker.poll-interval}")
	public void tick() {
		try {
			for (int batch = 0; batch < MAX_BATCHES_PER_TICK; batch++) {
				if (runOnce() < this.properties.worker().batchSize()) {
					return;
				}
			}
		}
		catch (RuntimeException ex) {
			// The next tick runs regardless; whatever this one had claimed is recovered through its lease.
			log.error("REMINDER_TICK_FAILED", ex);
		}
	}

	/** One full cycle. Returns how many rows the claim locked; a full batch means more may be due. */
	public int runOnce() {
		reclaimExpiredLeases();
		ClaimBatch batch = claimDueBatch();
		CompletableFuture<?>[] sends = batch.claimed()
			.stream()
			.map((claimed) -> CompletableFuture.runAsync(() -> this.delivery.deliverSafely(claimed), this.sendExecutor))
			.toArray(CompletableFuture[]::new);
		CompletableFuture.allOf(sends).join();
		return batch.lockedRows();
	}

	/**
	 * Step 1, RECLAIM: a PROCESSING reminder whose lease has passed belongs to a worker that crashed
	 * or hung. It goes back to PENDING and its open attempt becomes ABANDONED. Each reclaim is fenced
	 * by the claim token it saw, so instances reclaiming at the same time can't trip over each other.
	 */
	public int reclaimExpiredLeases() {
		Instant now = this.clock.instant();
		int reclaimed = 0;
		for (Reminder expired : this.reminders.findExpiredLeases(now, this.properties.worker().batchSize())) {
			boolean won = Boolean.TRUE.equals(this.transactions.execute((status) -> {
				if (this.reminders.reclaim(expired.getId(), expired.getClaimToken(), now) == 0) {
					return false;
				}
				this.attempts.markAbandoned(expired.getId(), expired.getClaimToken(), now);
				return true;
			}));
			if (won) {
				reclaimed++;
				log.warn("REMINDER_RECLAIMED reminderId={} key={} attempt={} claimedBy={} leaseExpiredAt={}",
						expired.getId(), expired.getIdempotencyKey(), expired.getAttemptCount(), expired.getClaimedBy(),
						expired.getProcessingUntil());
			}
		}
		return reclaimed;
	}

	/**
	 * Step 2, CLAIM (TX1): lock due reminders with SKIP LOCKED, decide each one, and mark the sendable
	 * ones PROCESSING with a fresh claim token and lease. This commits BEFORE anything is sent, and
	 * no transaction is held open during the provider call.
	 */
	public ClaimBatch claimDueBatch() {
		return this.transactions.execute((status) -> {
			Instant now = this.clock.instant();
			List<Reminder> due = this.reminders.lockDueBatch(now, this.properties.worker().batchSize());
			if (due.isEmpty()) {
				return new ClaimBatch(0, List.of());
			}
			Map<Long, Appointment> appointmentsById = this.appointments
				.findAllById(due.stream().map(Reminder::getAppointmentId).distinct().toList())
				.stream()
				.collect(Collectors.toMap(Appointment::getId, Function.identity()));
			List<ClaimedReminder> claimed = new ArrayList<>();
			for (Reminder reminder : due) {
				ClaimedReminder claim = claimOrClose(reminder, appointmentsById.get(reminder.getAppointmentId()), now);
				if (claim != null) {
					claimed.add(claim);
				}
			}
			AfterCommit.run(() -> claimed.forEach((claim) -> log.info(
					"REMINDER_CLAIMED reminderId={} key={} attempt={} leaseUntil={} by={}", claim.reminderId(),
					claim.idempotencyKey(), claim.attemptNo(), claim.processingUntil(), this.instanceId)));
			return new ClaimBatch(due.size(), claimed);
		});
	}

	private ClaimedReminder claimOrClose(Reminder reminder, Appointment appointment, Instant now) {
		// A reminder that was in flight during a cancel or reschedule, and then came back for a retry.
		if (appointment.getStatus() != AppointmentStatus.SCHEDULED) {
			return close(reminder, ReminderStatus.CANCELLED, "appointment cancelled", now);
		}
		if (reminder.getAppointmentRevision() != appointment.getRevision()) {
			return close(reminder, ReminderStatus.SUPERSEDED, "appointment rescheduled", now);
		}
		if (!this.policy.isSendable(reminder.getReminderType(), reminder.getDueAt(), appointment.getScheduledAt(), now)) {
			return close(reminder, ReminderStatus.EXPIRED, "outside its send window when picked up at " + now, now);
		}
		int maxAttempts = this.properties.worker().maxAttempts();
		if (reminder.getAttemptCount() >= maxAttempts) {
			return close(reminder, ReminderStatus.FAILED,
					"gave up after " + maxAttempts + " attempts; the last one never reported an outcome", now);
		}
		int attemptNo = reminder.getAttemptCount() + 1;
		Notification notification;
		try {
			notification = this.renderer.render(reminder, appointment, attemptNo);
		}
		catch (RuntimeException ex) {
			return close(reminder, ReminderStatus.FAILED, "could not render the message: " + ex.getMessage(), now);
		}
		String token = UUID.randomUUID().toString();
		Instant leaseUntil = now.plus(this.properties.worker().processingLease());
		this.reminders.markProcessing(reminder.getId(), token, this.instanceId, leaseUntil, now);
		this.attempts.save(NotificationAttempt.started(reminder.getId(), attemptNo, token, notification,
				this.jsonMapper.writeValueAsString(notification), now));
		return new ClaimedReminder(reminder.getId(), reminder.getIdempotencyKey(), reminder.getReminderType(),
				reminder.getDueAt(), appointment.getScheduledAt(), attemptNo, token, leaseUntil, notification);
	}

	private ClaimedReminder close(Reminder reminder, ReminderStatus status, String reason, Instant now) {
		this.reminders.closePending(reminder.getId(), status.name(), reason, now);
		AfterCommit.run(() -> log.info("REMINDER_{} reminderId={} key={} reason={}", status, reminder.getId(),
				reminder.getIdempotencyKey(), reason));
		return null;
	}

}
