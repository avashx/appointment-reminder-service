package com.dealership.appointmentreminder.entity;

import java.time.Instant;

/**
 * One row of {@code notification_delivery}: the durable record that an idempotency key was handed
 * to the provider. Not a JPA entity on purpose: it is written with plain SQL (see
 * {@code NotificationDeliveryRepository}) because the insert must commit on its own, before the
 * provider is called, and a duplicate key must be detected exactly.
 */
public record NotificationDelivery(String idempotencyKey, long reminderId, int attemptNo, Channel channel,
		DeliveryStatus status, String providerMessageId, Instant recordedAt, Instant acceptedAt) {

}
