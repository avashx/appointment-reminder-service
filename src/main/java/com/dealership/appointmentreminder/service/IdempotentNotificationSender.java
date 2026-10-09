package com.dealership.appointmentreminder.service;

import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.dealership.appointmentreminder.dto.Notification;
import com.dealership.appointmentreminder.entity.NotificationDelivery;
import com.dealership.appointmentreminder.repository.NotificationDeliveryRepository;

/**
 * Sending protection, wrapped around the real provider: before a message is handed over, its
 * idempotency key is inserted into {@code notification_delivery} and committed. Only one insert
 * per key can ever succeed, so only one attempt (on any thread, instance, or after any restart)
 * calls the provider; every other attempt is {@link SendResult.Suppressed} and sends nothing.
 *
 * <p>Ordering is the whole point: record, then send. A crash after the send leaves the record
 * behind, so the retry is suppressed instead of duplicated. The price is the reverse gap: a crash
 * after the record but before the provider call leaves a key that is recorded but never sent, i.e.
 * a missed reminder rather than a duplicate.
 */
@Primary
@Component
public class IdempotentNotificationSender implements NotificationSender {

	private static final Logger log = LoggerFactory.getLogger(IdempotentNotificationSender.class);

	private final NotificationSender provider;

	private final NotificationDeliveryRepository deliveries;

	private final Clock clock;

	/** {@code provider}: the real sender (the logging stub, or a test fake); never this bean itself. */
	public IdempotentNotificationSender(NotificationSender provider, NotificationDeliveryRepository deliveries,
			Clock clock) {
		this.provider = provider;
		this.deliveries = deliveries;
		this.clock = clock;
	}

	@Override
	public SendResult send(Notification notification) {
		if (TransactionSynchronizationManager.isActualTransactionActive()) {
			throw new IllegalStateException(
					"Called inside a transaction: the delivery record must commit before the provider is called");
		}
		String key = notification.idempotencyKey();
		boolean reserved;
		try {
			reserved = this.deliveries.reserve(key, notification.reminderId(), notification.attemptNo(),
					notification.channel(), this.clock.instant());
		}
		catch (DataAccessException ex) {
			log.warn("NOTIFICATION_RESERVE_FAILED key={} attempt={} error={}", key, notification.attemptNo(),
					ex.getMessage());
			return new SendResult.RetryableRejection("could not record the delivery, provider not called: " + ex.getMessage());
		}
		if (!reserved) {
			return suppressed(notification);
		}
		SendResult result;
		try {
			result = this.provider.send(notification);
		}
		catch (RuntimeException ex) {
			result = new SendResult.Unknown("provider call failed: " + ex);
		}
		if (result instanceof SendResult.Accepted accepted) {
			try {
				this.deliveries.markAccepted(key, notification.attemptNo(), accepted.providerMessageId(),
						this.clock.instant());
			}
			catch (DataAccessException ex) {
				// The provider has it and the record still blocks any resend; only the message id is missing.
				log.warn("NOTIFICATION_ACCEPT_NOT_RECORDED key={} providerMessageId={} error={}", key,
						accepted.providerMessageId(), ex.getMessage());
			}
		}
		return result;
	}

	private SendResult suppressed(Notification notification) {
		String key = notification.idempotencyKey();
		NotificationDelivery existing = this.deliveries.findByKey(key)
			.orElseThrow(() -> new IllegalStateException("Delivery record for " + key + " disappeared"));
		log.warn("NOTIFICATION_DUPLICATE_SUPPRESSED key={} attempt={} handedOverByAttempt={} deliveryStatus={} providerMessageId={}",
				key, notification.attemptNo(), existing.attemptNo(), existing.status(), existing.providerMessageId());
		return new SendResult.Suppressed(existing.attemptNo(), existing.providerMessageId());
	}

}
