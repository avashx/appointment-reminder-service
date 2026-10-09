package com.dealership.appointmentreminder.service;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import org.springframework.stereotype.Component;

import com.dealership.appointmentreminder.dto.Notification;
import com.dealership.appointmentreminder.entity.Appointment;
import com.dealership.appointmentreminder.entity.Channel;
import com.dealership.appointmentreminder.entity.Reminder;

/**
 * Turns a reminder into the message text. The time is always absolute ("Thu, Oct 8, 2026 at
 * 3:00 PM UTC"), never "tomorrow" or "today": a reminder that goes out late, or a 2-hour reminder
 * for a 00:30 appointment, would make a relative word wrong.
 */
@Component
public class MessageRenderer {

	private static final DateTimeFormatter WHEN = DateTimeFormatter
		.ofPattern("EEE, MMM d, yyyy 'at' h:mm a 'UTC'", Locale.US)
		.withZone(ZoneOffset.UTC);

	public Notification render(Reminder reminder, Appointment appointment, int attemptNo) {
		String when = WHEN.format(appointment.getScheduledAt());
		String firstName = appointment.getCustomerName().trim().split("\\s+")[0];
		String service = (appointment.getServiceType() != null) ? appointment.getServiceType() : "service";
		String vehicle = (appointment.getVehicle() != null) ? " for your " + appointment.getVehicle() : "";
		String body = switch (reminder.getReminderType()) {
			case REMINDER_24H -> "Hi %s, a reminder: your %s appointment%s is on %s. Need to reschedule? Just give us a call."
				.formatted(firstName, service, vehicle, when);
			case REMINDER_2H -> "Hi %s, see you soon! Your %s appointment%s is on %s."
				.formatted(firstName, service, vehicle, when);
		};
		// One reminder goes out on exactly one channel: SMS and email would be the same reminder twice.
		Channel channel = appointment.getPreferredChannel();
		String recipient = (channel == Channel.SMS) ? appointment.getCustomerPhone() : appointment.getCustomerEmail();
		if (recipient == null) {
			throw new IllegalStateException("appointment has no " + channel + " contact");
		}
		return new Notification(reminder.getIdempotencyKey(), appointment.getId(), reminder.getId(), attemptNo,
				appointment.getDealershipId(), reminder.getReminderType(), channel, recipient, body);
	}

}
