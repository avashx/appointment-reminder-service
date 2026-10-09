package com.dealership.appointmentreminder.dto;

import com.dealership.appointmentreminder.entity.Channel;
import com.dealership.appointmentreminder.entity.ReminderType;

/**
 * Everything a provider needs to deliver one message. {@code idempotencyKey} identifies the
 * logical reminder and is the same on every attempt; {@code attemptNo} says which attempt this is.
 */
public record Notification(String idempotencyKey, long appointmentId, long reminderId, int attemptNo,
		long dealershipId, ReminderType template, Channel channel, String recipient, String body) {

	public Notification withRecipient(String newRecipient) {
		return new Notification(this.idempotencyKey, this.appointmentId, this.reminderId, this.attemptNo,
				this.dealershipId, this.template, this.channel, newRecipient, this.body);
	}

}
