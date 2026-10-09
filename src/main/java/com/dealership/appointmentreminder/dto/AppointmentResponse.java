package com.dealership.appointmentreminder.dto;

import java.time.Instant;
import java.util.List;

import com.dealership.appointmentreminder.entity.Appointment;
import com.dealership.appointmentreminder.entity.AppointmentStatus;
import com.dealership.appointmentreminder.entity.Channel;
import com.dealership.appointmentreminder.entity.Reminder;

/** What the API returns. Contact details are deliberately left out: responses don't need to echo PII. */
public record AppointmentResponse(long id, long dealershipId, AppointmentStatus status, Instant scheduledAt,
		String customerName, Channel preferredChannel, String vehicle, String serviceType, int revision,
		List<ReminderResponse> reminders) {

	public static AppointmentResponse from(Appointment appointment, List<Reminder> reminders) {
		return new AppointmentResponse(appointment.getId(), appointment.getDealershipId(), appointment.getStatus(),
				appointment.getScheduledAt(), appointment.getCustomerName(), appointment.getPreferredChannel(),
				appointment.getVehicle(), appointment.getServiceType(), appointment.getRevision(),
				reminders.stream().map(ReminderResponse::from).toList());
	}

}
