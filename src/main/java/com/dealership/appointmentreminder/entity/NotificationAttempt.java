package com.dealership.appointmentreminder.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.dealership.appointmentreminder.dto.Notification;

/**
 * Append-only audit of every claim. It is written as STARTED in the same transaction as the claim,
 * before anything is sent, so a crash mid-send leaves evidence behind: the reclaim marks it
 * ABANDONED, and the next attempt gets the next number.
 */
@Entity
@Table(name = "notification_attempt")
public class NotificationAttempt {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	private Long reminderId;

	private int attemptNo;

	private String claimToken;

	private String idempotencyKey;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	private Channel channel;

	private String recipient;

	@Column(columnDefinition = "json")
	private String payload;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	private AttemptOutcome outcome;

	private String providerMessageId;

	private String error;

	private Instant startedAt;

	private Instant completedAt;

	protected NotificationAttempt() {
	}

	public static NotificationAttempt started(long reminderId, int attemptNo, String claimToken,
			Notification notification, String payloadJson, Instant now) {
		NotificationAttempt attempt = new NotificationAttempt();
		attempt.reminderId = reminderId;
		attempt.attemptNo = attemptNo;
		attempt.claimToken = claimToken;
		attempt.idempotencyKey = notification.idempotencyKey();
		attempt.channel = notification.channel();
		attempt.recipient = notification.recipient();
		attempt.payload = payloadJson;
		attempt.outcome = AttemptOutcome.STARTED;
		attempt.startedAt = now;
		return attempt;
	}

	public Long getId() {
		return this.id;
	}

	public Long getReminderId() {
		return this.reminderId;
	}

	public int getAttemptNo() {
		return this.attemptNo;
	}

	public String getClaimToken() {
		return this.claimToken;
	}

	public String getIdempotencyKey() {
		return this.idempotencyKey;
	}

	public Channel getChannel() {
		return this.channel;
	}

	public String getRecipient() {
		return this.recipient;
	}

	public String getPayload() {
		return this.payload;
	}

	public AttemptOutcome getOutcome() {
		return this.outcome;
	}

	public String getProviderMessageId() {
		return this.providerMessageId;
	}

	public String getError() {
		return this.error;
	}

	public Instant getStartedAt() {
		return this.startedAt;
	}

	public Instant getCompletedAt() {
		return this.completedAt;
	}

}
