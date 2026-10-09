package com.dealership.appointmentreminder.entity;

import java.time.Instant;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.dealership.appointmentreminder.dto.AppointmentDetails;

@Entity
@Table(name = "appointment")
public class Appointment {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	private Long dealershipId;

	private String customerName;

	private String customerPhone;

	private String customerEmail;

	// Hibernate 6+ maps enums to native MySQL ENUM columns by default; ours are VARCHAR.
	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	private Channel preferredChannel;

	private String vehicle;

	private String serviceType;

	private Instant scheduledAt;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	private AppointmentStatus status;

	/** Bumped on every reschedule; part of each reminder's identity. */
	private int revision;

	private String idempotencyKey;

	private String requestHash;

	/** Optimistic lock: a concurrent cancel/reschedule fails instead of silently overwriting. */
	@Version
	private long version;

	private Instant createdAt;

	private Instant updatedAt;

	protected Appointment() {
	}

	public static Appointment schedule(AppointmentDetails details, String idempotencyKey, String requestHash,
			Instant now) {
		Appointment appointment = new Appointment();
		appointment.dealershipId = details.dealershipId();
		appointment.customerName = details.customerName();
		appointment.customerPhone = details.customerPhone();
		appointment.customerEmail = details.customerEmail();
		appointment.preferredChannel = details.preferredChannel();
		appointment.vehicle = details.vehicle();
		appointment.serviceType = details.serviceType();
		appointment.scheduledAt = details.scheduledAt();
		appointment.status = AppointmentStatus.SCHEDULED;
		appointment.revision = 1;
		appointment.idempotencyKey = idempotencyKey;
		appointment.requestHash = requestHash;
		appointment.createdAt = now;
		appointment.updatedAt = now;
		return appointment;
	}

	public boolean hasStarted(Instant now) {
		return !now.isBefore(this.scheduledAt);
	}

	public void cancel(Instant now) {
		this.status = AppointmentStatus.CANCELLED;
		this.updatedAt = now;
	}

	public void reschedule(Instant newScheduledAt, Instant now) {
		this.scheduledAt = newScheduledAt;
		this.revision++;
		this.updatedAt = now;
	}

	public Long getId() {
		return this.id;
	}

	public Long getDealershipId() {
		return this.dealershipId;
	}

	public String getCustomerName() {
		return this.customerName;
	}

	public String getCustomerPhone() {
		return this.customerPhone;
	}

	public String getCustomerEmail() {
		return this.customerEmail;
	}

	public Channel getPreferredChannel() {
		return this.preferredChannel;
	}

	public String getVehicle() {
		return this.vehicle;
	}

	public String getServiceType() {
		return this.serviceType;
	}

	public Instant getScheduledAt() {
		return this.scheduledAt;
	}

	public AppointmentStatus getStatus() {
		return this.status;
	}

	public int getRevision() {
		return this.revision;
	}

	public String getRequestHash() {
		return this.requestHash;
	}

}
