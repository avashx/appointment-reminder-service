package com.dealership.appointmentreminder.service;

import com.dealership.appointmentreminder.dto.Notification;

/**
 * Port to the outside world (SMS/email provider). A Twilio or SendGrid adapter would implement it
 * and map responses to {@link SendResult}. The contract that keeps duplicates out: return a
 * rejection ONLY when the provider definitely did not accept the message. When in doubt (timeout,
 * reset, 5xx) return {@link SendResult.Unknown} or throw; both are treated as "may have been delivered".
 */
public interface NotificationSender {

	SendResult send(Notification notification);

}
