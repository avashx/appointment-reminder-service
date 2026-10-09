package com.dealership.appointmentreminder.service;

import java.time.Instant;

import com.dealership.appointmentreminder.dto.Notification;
import com.dealership.appointmentreminder.entity.ReminderType;

/**
 * A reminder this worker owns until {@code processingUntil}, as committed by the claim. Everything
 * Steps 3 and 4 need is captured here, so they never re-read state another worker may be changing.
 */
public record ClaimedReminder(long reminderId, String idempotencyKey, ReminderType type, Instant dueAt,
		Instant appointmentAt, int attemptNo, String claimToken, Instant processingUntil, Notification notification) {

}
