package com.dealership.appointmentreminder.service;

import java.time.Duration;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Fallback;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import com.dealership.appointmentreminder.dto.Notification;

/**
 * The stub required by the brief: it logs the payload and "accepts" it, sending nothing. It is the
 * provider behind {@link IdempotentNotificationSender}; {@code @Fallback} lets a test register its
 * own fake provider without any qualifier juggling. {@code notification.stub.latency} simulates a
 * slow provider so the demo can kill the process mid-send.
 */
@Fallback
@Component
public class LoggingNotificationSender implements NotificationSender {

	private static final Logger log = LoggerFactory.getLogger(LoggingNotificationSender.class);

	private final JsonMapper jsonMapper;

	private final Duration latency;

	public LoggingNotificationSender(JsonMapper jsonMapper, @Value("${notification.stub.latency:PT0S}") Duration latency) {
		this.jsonMapper = jsonMapper;
		this.latency = latency;
	}

	@Override
	public SendResult send(Notification notification) {
		Notification forLog = notification.withRecipient(Masking.mask(notification.channel(), notification.recipient()));
		log.info("NOTIFICATION_STUB_SEND key={} attempt={} channel={} to={} payload={}", notification.idempotencyKey(),
				notification.attemptNo(), notification.channel(), forLog.recipient(),
				this.jsonMapper.writeValueAsString(forLog));
		if (!this.latency.isZero()) {
			try {
				Thread.sleep(this.latency);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				return new SendResult.Unknown("interrupted while the provider call was in flight");
			}
		}
		String providerMessageId = "stub-" + UUID.randomUUID();
		log.info("NOTIFICATION_STUB_ACCEPTED key={} providerMessageId={}", notification.idempotencyKey(),
				providerMessageId);
		return new SendResult.Accepted(providerMessageId);
	}

}
