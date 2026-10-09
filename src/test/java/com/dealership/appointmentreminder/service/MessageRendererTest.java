package com.dealership.appointmentreminder.service;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.dealership.appointmentreminder.dto.AppointmentDetails;
import com.dealership.appointmentreminder.dto.Notification;
import com.dealership.appointmentreminder.entity.Appointment;
import com.dealership.appointmentreminder.entity.Channel;
import com.dealership.appointmentreminder.entity.Reminder;
import com.dealership.appointmentreminder.entity.ReminderStatus;
import com.dealership.appointmentreminder.entity.ReminderType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class MessageRendererTest {

	private static final Instant THU_OCT_8_1500Z = Instant.parse("2026-10-08T15:00:00Z");

	private final MessageRenderer renderer = new MessageRenderer();

	@Test
	void rendersTheDayBeforeReminderWithAnAbsoluteTime() {
		Notification notification = this.renderer.render(reminder(ReminderType.REMINDER_24H),
				appointment(Channel.SMS, "+13125550142", null, "2021 Honda Civic", "Oil change"), 1);

		assertThat(notification.body()).isEqualTo("Hi Alex, a reminder: your Oil change appointment for your "
				+ "2021 Honda Civic is on Thu, Oct 8, 2026 at 3:00 PM UTC. Need to reschedule? Just give us a call.");
		assertThat(notification.body()).doesNotContainIgnoringCase("tomorrow").doesNotContainIgnoringCase("today");
	}

	@Test
	void rendersTheTwoHourReminder() {
		Notification notification = this.renderer.render(reminder(ReminderType.REMINDER_2H),
				appointment(Channel.SMS, "+13125550142", null, "2021 Honda Civic", "Oil change"), 1);

		assertThat(notification.body()).isEqualTo(
				"Hi Alex, see you soon! Your Oil change appointment for your 2021 Honda Civic is on Thu, Oct 8, 2026 at 3:00 PM UTC.");
	}

	@Test
	void optionalDetailsFallBackToNeutralWording() {
		Notification notification = this.renderer.render(reminder(ReminderType.REMINDER_2H),
				appointment(Channel.SMS, "+13125550142", null, null, null), 1);

		assertThat(notification.body()).startsWith("Hi Alex, see you soon! Your service appointment is on");
	}

	@Test
	void carriesTheReminderIdentityAndTheAttemptNumber() {
		Notification notification = this.renderer.render(reminder(ReminderType.REMINDER_24H),
				appointment(Channel.SMS, "+13125550142", null, "2021 Honda Civic", "Oil change"), 3);

		assertThat(notification.idempotencyKey()).isEqualTo("appt-42-r1-REMINDER_24H");
		assertThat(notification.attemptNo()).isEqualTo(3);
		assertThat(notification.reminderId()).isEqualTo(7L);
		assertThat(notification.appointmentId()).isEqualTo(42L);
		assertThat(notification.dealershipId()).isEqualTo(1L);
		assertThat(notification.template()).isEqualTo(ReminderType.REMINDER_24H);
		assertThat(notification.channel()).isEqualTo(Channel.SMS);
		assertThat(notification.recipient()).isEqualTo("+13125550142");
	}

	@Test
	void theEmailChannelUsesTheEmailAddress() {
		Notification notification = this.renderer.render(reminder(ReminderType.REMINDER_24H),
				appointment(Channel.EMAIL, "+13125550142", "alex@example.com", null, null), 1);

		assertThat(notification.channel()).isEqualTo(Channel.EMAIL);
		assertThat(notification.recipient()).isEqualTo("alex@example.com");
	}

	@Test
	void failsLoudlyIfTheChosenChannelHasNoContact() {
		Appointment noEmail = appointment(Channel.EMAIL, "+13125550142", null, null, null);

		assertThatIllegalStateException()
			.isThrownBy(() -> this.renderer.render(reminder(ReminderType.REMINDER_24H), noEmail, 1));
	}

	private static Appointment appointment(Channel channel, String phone, String email, String vehicle,
			String serviceType) {
		Appointment appointment = Appointment.schedule(new AppointmentDetails(1L, "Alex Doe", phone, email, channel,
				vehicle, serviceType, THU_OCT_8_1500Z), null, null, Instant.EPOCH);
		ReflectionTestUtils.setField(appointment, "id", 42L);
		return appointment;
	}

	private static Reminder reminder(ReminderType type) {
		Reminder reminder = Reminder.planned(42, 1, type, THU_OCT_8_1500Z, ReminderStatus.PENDING, null, Instant.EPOCH);
		ReflectionTestUtils.setField(reminder, "id", 7L);
		return reminder;
	}

}
