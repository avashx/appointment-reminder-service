package com.dealership.appointmentreminder.dto;

import java.time.Instant;

import com.dealership.appointmentreminder.entity.Reminder;
import com.dealership.appointmentreminder.entity.ReminderStatus;
import com.dealership.appointmentreminder.entity.ReminderType;

public record ReminderResponse(long id, ReminderType type, int revision, ReminderStatus status, String idempotencyKey,
		Instant dueAt, Instant sentAt, int attemptCount, String statusReason, String providerMessageId) {

	public static ReminderResponse from(Reminder reminder) {
		return new ReminderResponse(reminder.getId(), reminder.getReminderType(), reminder.getAppointmentRevision(),
				reminder.getStatus(), reminder.getIdempotencyKey(), reminder.getDueAt(), reminder.getSentAt(),
				reminder.getAttemptCount(), reminder.getStatusReason(), reminder.getProviderMessageId());
	}

}
