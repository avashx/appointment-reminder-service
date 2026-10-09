package com.dealership.appointmentreminder.service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.dealership.appointmentreminder.config.ReminderProperties;
import com.dealership.appointmentreminder.config.ReminderProperties.Booking;
import com.dealership.appointmentreminder.config.ReminderProperties.TypeSettings;
import com.dealership.appointmentreminder.config.ReminderProperties.Worker;
import com.dealership.appointmentreminder.entity.Reminder;

import static com.dealership.appointmentreminder.entity.ReminderStatus.PENDING;
import static com.dealership.appointmentreminder.entity.ReminderStatus.SKIPPED;
import static com.dealership.appointmentreminder.entity.ReminderType.REMINDER_24H;
import static com.dealership.appointmentreminder.entity.ReminderType.REMINDER_2H;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/** Pure timing rules: no Spring, no database, fixed instants. */
class ReminderPolicyTest {

	private static final Instant NOW = Instant.parse("2026-10-06T09:00:00Z");

	private static final ZoneId CHICAGO = ZoneId.of("America/Chicago");

	private final ReminderPolicy policy = new ReminderPolicy(properties(Duration.ofMinutes(90)));

	@Test
	void bookedMoreThanADayAheadPlansBothReminders() {
		Instant appointment = NOW.plus(Duration.ofDays(2));

		List<Reminder> plan = this.policy.plan(42, 1, appointment, NOW);

		assertThat(plan).extracting(Reminder::getReminderType, Reminder::getStatus, Reminder::getDueAt)
			.containsExactly(tuple(REMINDER_24H, PENDING, appointment.minus(Duration.ofHours(24))),
					tuple(REMINDER_2H, PENDING, appointment.minus(Duration.ofHours(2))));
		assertThat(plan).extracting(Reminder::getIdempotencyKey)
			.containsExactly("appt-42-r1-REMINDER_24H", "appt-42-r1-REMINDER_2H");
	}

	@Test
	void theIdempotencyKeyIsDerivedSoEveryRetryCarriesTheSameOne() {
		assertThat(Reminder.idempotencyKey(42, 3, REMINDER_2H)).isEqualTo("appt-42-r3-REMINDER_2H")
			.isEqualTo(this.policy.plan(42, 3, NOW.plus(Duration.ofDays(2)), NOW).get(1).getIdempotencyKey());
	}

	@Test
	void bookedInsideTheDayBeforeWindowSkipsOnlyTheDayBeforeReminder() {
		List<Reminder> plan = this.policy.plan(1, 1, NOW.plus(Duration.ofHours(5)), NOW);

		assertThat(plan).extracting(Reminder::getStatus).containsExactly(SKIPPED, PENDING);
		assertThat(plan.get(0).getStatusReason()).isEqualTo(ReminderPolicy.WINDOW_PASSED_AT_BOOKING);
	}

	@Test
	void bookedLessThanTwoHoursAheadSkipsBoth() {
		assertThat(this.policy.plan(1, 1, NOW.plus(Duration.ofMinutes(90)), NOW)).extracting(Reminder::getStatus)
			.containsExactly(SKIPPED, SKIPPED);
	}

	@Test
	void aDueTimeExactlyEqualToNowIsStillPending() {
		Reminder dayBefore = this.policy.plan(1, 1, NOW.plus(Duration.ofHours(24)), NOW).get(0);

		assertThat(dayBefore.getDueAt()).isEqualTo(NOW);
		assertThat(dayBefore.getStatus()).isEqualTo(PENDING);
	}

	@Test
	void twentyFourHoursMeansElapsedTimeWhenDaylightSavingEnds() {
		// Sun 1 Nov 2026 10:00 in Chicago is CST (UTC-6); clocks fell back at 02:00 that morning.
		Instant appointment = ZonedDateTime.of(2026, 11, 1, 10, 0, 0, 0, CHICAGO).toInstant();

		Reminder dayBefore = this.policy.plan(1, 1, appointment, NOW).get(0);

		// 24 elapsed hours earlier is Saturday 11:00 CDT, not 10:00.
		assertThat(dayBefore.getDueAt().atZone(CHICAGO).toLocalDateTime()).isEqualTo(LocalDateTime.of(2026, 10, 31, 11, 0));
	}

	@Test
	void twentyFourHoursMeansElapsedTimeWhenDaylightSavingStarts() {
		// Sun 14 Mar 2027 10:00 in Chicago is CDT (UTC-5); clocks sprang forward at 02:00 that morning.
		Instant appointment = ZonedDateTime.of(2027, 3, 14, 10, 0, 0, 0, CHICAGO).toInstant();

		Reminder dayBefore = this.policy.plan(1, 1, appointment, NOW).get(0);

		assertThat(dayBefore.getDueAt().atZone(CHICAGO).toLocalDateTime()).isEqualTo(LocalDateTime.of(2027, 3, 13, 9, 0));
	}

	@Test
	void aReminderIsSendableOnlyUntilTheEndOfItsLatenessWindow() {
		Instant appointment = NOW.plus(Duration.ofDays(2));
		Instant dueAt = appointment.minus(Duration.ofHours(24));
		Instant windowEnd = dueAt.plus(Duration.ofHours(6));

		assertThat(this.policy.isSendable(REMINDER_24H, dueAt, appointment, dueAt.minusMillis(1))).isFalse();
		assertThat(this.policy.isSendable(REMINDER_24H, dueAt, appointment, dueAt)).isTrue();
		assertThat(this.policy.isSendable(REMINDER_24H, dueAt, appointment, windowEnd)).isTrue();
		assertThat(this.policy.isSendable(REMINDER_24H, dueAt, appointment, windowEnd.plusMillis(1))).isFalse();
	}

	@Test
	void nothingIsSendableOnceTheAppointmentHasStarted() {
		// A (silly) 3h lateness for the 2h reminder would reach past the start time; the start time still wins.
		ReminderPolicy generous = new ReminderPolicy(properties(Duration.ofHours(3)));
		Instant appointment = NOW.plus(Duration.ofDays(1));
		Instant dueAt = appointment.minus(Duration.ofHours(2));

		assertThat(generous.isSendable(REMINDER_2H, dueAt, appointment, appointment.minusMillis(1))).isTrue();
		assertThat(generous.isSendable(REMINDER_2H, dueAt, appointment, appointment)).isFalse();
	}

	@Test
	void withTheDefaultSettingsBothRemindersAreNeverSendableAtTheSameTime() {
		Instant appointment = NOW.plus(Duration.ofDays(2));
		List<Reminder> plan = this.policy.plan(1, 1, appointment, NOW);
		Instant dayBefore = plan.get(0).getDueAt();
		Instant twoHoursBefore = plan.get(1).getDueAt();

		for (Instant t = appointment.minus(Duration.ofHours(25)); t.isBefore(appointment); t = t.plus(Duration.ofMinutes(1))) {
			boolean both = this.policy.isSendable(REMINDER_24H, dayBefore, appointment, t)
					&& this.policy.isSendable(REMINDER_2H, twoHoursBefore, appointment, t);
			assertThat(both).as("both sendable at %s", t).isFalse();
		}
	}

	@Test
	void backoffGrowsExponentiallyWithJitterAndIsCapped() {
		for (int i = 0; i < 200; i++) {
			assertThat(this.policy.backoff(1)).isBetween(Duration.ofSeconds(24), Duration.ofSeconds(36));
			assertThat(this.policy.backoff(2)).isBetween(Duration.ofSeconds(48), Duration.ofSeconds(72));
			assertThat(this.policy.backoff(12)).isBetween(Duration.ofMinutes(8), Duration.ofMinutes(12));
		}
	}

	@Test
	void retriesStopAfterMaxAttemptsOrWhenTheRetryWouldLandTooLate() {
		Instant appointment = NOW.plus(Duration.ofDays(2));
		Instant dueAt = appointment.minus(Duration.ofHours(24));

		assertThat(this.policy.nextRetryAt(REMINDER_24H, dueAt, 1, appointment, dueAt)).isPresent();
		assertThat(this.policy.nextRetryAt(REMINDER_24H, dueAt, 4, appointment, dueAt)).isPresent();
		assertThat(this.policy.nextRetryAt(REMINDER_24H, dueAt, 5, appointment, dueAt)).as("max attempts is 5").isEmpty();
		Instant justBeforeWindowEnd = dueAt.plus(Duration.ofHours(6)).minusSeconds(5);
		assertThat(this.policy.nextRetryAt(REMINDER_24H, dueAt, 1, appointment, justBeforeWindowEnd)).isEmpty();
	}

	private static ReminderProperties properties(Duration twoHourLateness) {
		return new ReminderProperties(
				Map.of(REMINDER_24H, new TypeSettings(Duration.ofHours(24), Duration.ofHours(6)), REMINDER_2H,
						new TypeSettings(Duration.ofHours(2), twoHourLateness)),
				new Worker(true, Duration.ofSeconds(5), 200, 8, Duration.ofMinutes(2), 5, Duration.ofSeconds(30),
						Duration.ofMinutes(10)),
				new Booking(Duration.ofDays(365)));
	}

}
