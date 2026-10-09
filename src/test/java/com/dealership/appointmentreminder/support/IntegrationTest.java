package com.dealership.appointmentreminder.support;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.dealership.appointmentreminder.TestcontainersConfiguration;
import com.dealership.appointmentreminder.config.ReminderProperties;
import com.dealership.appointmentreminder.dto.AppointmentDetails;
import com.dealership.appointmentreminder.dto.AppointmentResponse;
import com.dealership.appointmentreminder.entity.Channel;
import com.dealership.appointmentreminder.entity.Reminder;
import com.dealership.appointmentreminder.entity.ReminderType;
import com.dealership.appointmentreminder.repository.ReminderRepository;
import com.dealership.appointmentreminder.scheduler.ReminderWorker;
import com.dealership.appointmentreminder.service.AppointmentService;
import com.dealership.appointmentreminder.support.RecordingNotificationSender.SimulatedCrash;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Base for tests that run the whole application against a real MySQL 8.4 in Docker. Every
 * subclass shares one Spring context and one container. The scheduled worker is switched off, so
 * each test drives the dispatch cycle itself and nothing runs in the background.
 *
 * <p>After EVERY test, the "never twice" invariants are checked against the database and against
 * what the fake provider actually received. {@code scripts/demo.sh invariants} runs the same SQL.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({ TestcontainersConfiguration.class, TestSupportConfig.class })
public abstract class IntegrationTest {

	protected static final Instant T0 = TestSupportConfig.T0;

	@Autowired
	protected AppointmentService appointmentService;

	@Autowired
	protected ReminderRepository reminderRepository;

	@Autowired
	protected ReminderWorker worker;

	@Autowired
	protected ReminderProperties properties;

	@Autowired
	protected MutableClock clock;

	@Autowired
	protected RecordingNotificationSender sender;

	@Autowired
	protected JdbcTemplate jdbc;

	@AfterEach
	void verifyInvariantsThenCleanUp() {
		try {
			assertThat(this.sender.keysPossiblyDeliveredMoreThanOnce())
				.as("no customer may receive the same reminder twice").isEmpty();
			assertThat(this.jdbc.queryForList("""
					SELECT appointment_id, appointment_revision, reminder_type FROM reminder
					 GROUP BY appointment_id, appointment_revision, reminder_type HAVING COUNT(*) > 1"""))
				.as("I1: one row per logical reminder").isEmpty();
			assertThat(this.jdbc.queryForList("""
					SELECT r.id FROM reminder r
					  LEFT JOIN notification_delivery d ON d.idempotency_key = r.idempotency_key
					 WHERE r.status = 'SENT' AND d.idempotency_key IS NULL"""))
				.as("I2: every SENT reminder is recorded in notification_delivery").isEmpty();
			assertThat(this.jdbc.queryForList("""
					SELECT reminder_id FROM notification_attempt WHERE outcome = 'ACCEPTED'
					 GROUP BY reminder_id HAVING COUNT(*) > 1"""))
				.as("I3: at most one attempt per reminder was accepted by the provider").isEmpty();
		}
		finally {
			this.jdbc.update("DELETE FROM notification_delivery");
			this.jdbc.update("DELETE FROM notification_attempt");
			this.jdbc.update("DELETE FROM reminder");
			this.jdbc.update("DELETE FROM appointment");
			this.clock.reset();
			this.sender.reset();
		}
	}

	protected AppointmentDetails details(Instant scheduledAt) {
		return new AppointmentDetails(1L, "Alex Doe", "+13125550142", null, Channel.SMS, "2021 Honda Civic",
				"Oil change", scheduledAt);
	}

	/** Books an appointment for {@code T0 + inFuture} with no idempotency key. */
	protected AppointmentResponse book(Duration inFuture) {
		return this.appointmentService.create(details(T0.plus(inFuture)), null).appointment();
	}

	/** Books {@code count} appointments whose 24h reminders are all due at the moment this returns. */
	protected List<Reminder> bookDayBeforeRemindersAllDueNow(int count) {
		List<Reminder> reminders = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			reminders.add(reminder(book(Duration.ofDays(3).plusMinutes(i)), ReminderType.REMINDER_24H));
		}
		this.clock.setInstant(T0.plus(Duration.ofDays(2)).plus(Duration.ofMinutes(count + 5)));
		return reminders;
	}

	protected Reminder reminder(AppointmentResponse appointment, ReminderType type) {
		return reminderOf(appointment.id(), type);
	}

	/** Fresh from the database: the latest revision's reminder of that type. */
	protected Reminder reminderOf(long appointmentId, ReminderType type) {
		return this.reminderRepository.findByAppointmentIdOrderByAppointmentRevisionAscDueAtAsc(appointmentId)
			.stream()
			.filter((reminder) -> reminder.getReminderType() == type)
			.reduce((first, second) -> second)
			.orElseThrow();
	}

	protected Reminder reload(Reminder reminder) {
		return this.reminderRepository.findById(reminder.getId()).orElseThrow();
	}

	protected List<String> attemptOutcomes(Reminder reminder) {
		return this.jdbc.queryForList("SELECT outcome FROM notification_attempt WHERE reminder_id = ? ORDER BY attempt_no",
				String.class, reminder.getId());
	}

	/** The reminder's row in notification_delivery, if any: the durable record that it was handed to the provider. */
	protected Optional<Map<String, Object>> deliveryOf(Reminder reminder) {
		return this.jdbc.queryForList("SELECT * FROM notification_delivery WHERE idempotency_key = ?",
				reminder.getIdempotencyKey()).stream().findFirst();
	}

	/** Moves the clock just past the processing lease, as if a worker had crashed and time went on. */
	protected void waitOutTheLease() {
		this.clock.advance(this.properties.worker().processingLease().plusSeconds(1));
	}

	/** One dispatch cycle in which a send "kills the process"; every other send in the batch still completes. */
	protected void runOnceSurvivingCrash() {
		try {
			this.worker.runOnce();
		}
		catch (CompletionException ex) {
			if (!(ex.getCause() instanceof SimulatedCrash)) {
				throw ex;
			}
		}
	}

	protected static LocalDateTime utc(Instant instant) {
		return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
	}

	/** Starts {@code threads} tasks at the same instant (a start gate) and waits for all of them. */
	protected static void runConcurrently(int threads, ThrowingIntConsumer task) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		try {
			CountDownLatch startGate = new CountDownLatch(1);
			List<Future<Void>> futures = new ArrayList<>();
			for (int i = 0; i < threads; i++) {
				int thread = i;
				Callable<Void> callable = () -> {
					startGate.await();
					task.accept(thread);
					return null;
				};
				futures.add(pool.submit(callable));
			}
			startGate.countDown();
			for (Future<Void> future : futures) {
				future.get(60, TimeUnit.SECONDS);
			}
		}
		finally {
			pool.shutdownNow();
		}
	}

	@FunctionalInterface
	protected interface ThrowingIntConsumer {

		void accept(int thread) throws Exception;

	}

}
