package com.dealership.appointmentreminder.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.dealership.appointmentreminder.entity.Channel;
import com.dealership.appointmentreminder.entity.DeliveryStatus;
import com.dealership.appointmentreminder.entity.NotificationDelivery;

/**
 * Sending protection: plain SQL on {@code notification_delivery}. Outside a transaction each
 * statement commits on its own, which is what Step 3 needs: the reservation is durable before the
 * provider is called. Times are bound as UTC LocalDateTime, the same values Hibernate writes.
 */
@Repository
public class NotificationDeliveryRepository {

	private final JdbcClient jdbc;

	public NotificationDeliveryRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Records that this key is being handed to the provider. The primary key makes this a one-time
	 * operation per key across threads, instances and restarts. Returns false if the key is already
	 * recorded, i.e. some attempt has already handed this reminder over.
	 */
	public boolean reserve(String idempotencyKey, long reminderId, int attemptNo, Channel channel, Instant now) {
		try {
			this.jdbc.sql("""
					INSERT INTO notification_delivery (idempotency_key, reminder_id, attempt_no, channel, status, recorded_at)
					VALUES (:key, :reminderId, :attemptNo, :channel, 'RESERVED', :now)""")
				.param("key", idempotencyKey)
				.param("reminderId", reminderId)
				.param("attemptNo", attemptNo)
				.param("channel", channel.name())
				.param("now", utc(now))
				.update();
			return true;
		}
		catch (DuplicateKeyException ex) {
			return false;
		}
	}

	public Optional<NotificationDelivery> findByKey(String idempotencyKey) {
		return this.jdbc.sql("SELECT * FROM notification_delivery WHERE idempotency_key = :key")
			.param("key", idempotencyKey)
			.query(NotificationDeliveryRepository::map)
			.optional();
	}

	/** The provider acknowledged the hand-off made by {@code attemptNo}. */
	public int markAccepted(String idempotencyKey, int attemptNo, String providerMessageId, Instant now) {
		return this.jdbc.sql("""
				UPDATE notification_delivery
				   SET status = 'ACCEPTED', provider_message_id = :providerMessageId, accepted_at = :now
				 WHERE idempotency_key = :key AND attempt_no = :attemptNo""")
			.param("providerMessageId", providerMessageId)
			.param("now", utc(now))
			.param("key", idempotencyKey)
			.param("attemptNo", attemptNo)
			.update();
	}

	/**
	 * The provider definitely did not accept the hand-off made by {@code attemptNo}, so the key may be
	 * handed over again. Only called inside the fenced outcome transaction, by the attempt that still
	 * owns the reminder.
	 */
	public int release(String idempotencyKey, int attemptNo) {
		return this.jdbc.sql("""
				DELETE FROM notification_delivery
				 WHERE idempotency_key = :key AND attempt_no = :attemptNo AND status = 'RESERVED'""")
			.param("key", idempotencyKey)
			.param("attemptNo", attemptNo)
			.update();
	}

	private static LocalDateTime utc(Instant instant) {
		return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
	}

	private static Instant instant(ResultSet rs, String column) throws SQLException {
		LocalDateTime value = rs.getObject(column, LocalDateTime.class);
		return (value != null) ? value.toInstant(ZoneOffset.UTC) : null;
	}

	private static NotificationDelivery map(ResultSet rs, int rowNum) throws SQLException {
		return new NotificationDelivery(rs.getString("idempotency_key"), rs.getLong("reminder_id"),
				rs.getInt("attempt_no"), Channel.valueOf(rs.getString("channel")),
				DeliveryStatus.valueOf(rs.getString("status")), rs.getString("provider_message_id"),
				instant(rs, "recorded_at"), instant(rs, "accepted_at"));
	}

}
