package com.dealership.appointmentreminder.entity;

import java.time.Instant;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One scheduled notification, and the work-queue row for it. After it is inserted, the entity is
 * never modified through setters: every state change is a guarded UPDATE in
 * {@code ReminderRepository}, so concurrent workers, reclaims and cancellations can't overwrite
 * each other.
 */
@Entity
@Table(name = "reminder")
public class Reminder {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	private Long appointmentId;

	private int appointmentRevision;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	private ReminderType reminderType;

	private Instant dueAt;

	private Instant nextAttemptAt;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	private ReminderStatus status;

	private String statusReason;

	private String idempotencyKey;

	private int attemptCount;

	private String claimToken;

	private String claimedBy;

	private Instant claimedAt;

	private Instant processingUntil;

	private Instant sentAt;

	private String providerMessageId;

	private Instant createdAt;

	private Instant updatedAt;

	protected Reminder() {
	}

	public static Reminder planned(long appointmentId, int revision, ReminderType type, Instant dueAt,
			ReminderStatus status, String statusReason, Instant now) {
		Reminder reminder = new Reminder();
		reminder.appointmentId = appointmentId;
		reminder.appointmentRevision = revision;
		reminder.reminderType = type;
		reminder.dueAt = dueAt;
		reminder.nextAttemptAt = dueAt;
		reminder.status = status;
		reminder.statusReason = statusReason;
		reminder.idempotencyKey = idempotencyKey(appointmentId, revision, type);
		reminder.createdAt = now;
		reminder.updatedAt = now;
		return reminder;
	}

	/**
	 * Derived, not generated: the same logical reminder always has the same key, on every retry,
	 * restart and instance. That is what lets {@code notification_delivery} recognise a repeat.
	 */
	public static String idempotencyKey(long appointmentId, int revision, ReminderType type) {
		return "appt-%d-r%d-%s".formatted(appointmentId, revision, type);
	}

	public Long getId() {
		return this.id;
	}

	public Long getAppointmentId() {
		return this.appointmentId;
	}

	public int getAppointmentRevision() {
		return this.appointmentRevision;
	}

	public ReminderType getReminderType() {
		return this.reminderType;
	}

	public Instant getDueAt() {
		return this.dueAt;
	}

	public Instant getNextAttemptAt() {
		return this.nextAttemptAt;
	}

	public ReminderStatus getStatus() {
		return this.status;
	}

	public String getStatusReason() {
		return this.statusReason;
	}

	public String getIdempotencyKey() {
		return this.idempotencyKey;
	}

	public int getAttemptCount() {
		return this.attemptCount;
	}

	public String getClaimToken() {
		return this.claimToken;
	}

	public String getClaimedBy() {
		return this.claimedBy;
	}

	public Instant getClaimedAt() {
		return this.claimedAt;
	}

	public Instant getProcessingUntil() {
		return this.processingUntil;
	}

	public Instant getSentAt() {
		return this.sentAt;
	}

	public String getProviderMessageId() {
		return this.providerMessageId;
	}

}
