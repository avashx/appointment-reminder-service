package com.dealership.appointmentreminder.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.dealership.appointmentreminder.config.ReminderProperties;
import com.dealership.appointmentreminder.dto.AppointmentDetails;
import com.dealership.appointmentreminder.dto.AppointmentResponse;
import com.dealership.appointmentreminder.entity.Appointment;
import com.dealership.appointmentreminder.entity.AppointmentStatus;
import com.dealership.appointmentreminder.entity.Reminder;
import com.dealership.appointmentreminder.entity.ReminderStatus;
import com.dealership.appointmentreminder.exception.AppointmentConflictException;
import com.dealership.appointmentreminder.exception.AppointmentNotFoundException;
import com.dealership.appointmentreminder.exception.BookingRejectedException;
import com.dealership.appointmentreminder.exception.InvalidBookingException;
import com.dealership.appointmentreminder.repository.AppointmentRepository;
import com.dealership.appointmentreminder.repository.ReminderRepository;

@Service
public class AppointmentService {

	private static final Logger log = LoggerFactory.getLogger(AppointmentService.class);

	private final AppointmentRepository appointments;

	private final ReminderRepository reminders;

	private final ReminderPolicy policy;

	private final ReminderProperties properties;

	private final TransactionTemplate transactions;

	private final Clock clock;

	public AppointmentService(AppointmentRepository appointments, ReminderRepository reminders, ReminderPolicy policy,
			ReminderProperties properties, TransactionTemplate transactions, Clock clock) {
		this.appointments = appointments;
		this.reminders = reminders;
		this.policy = policy;
		this.properties = properties;
		this.transactions = transactions;
		this.clock = clock;
	}

	public record CreateResult(AppointmentResponse appointment, boolean replayed) {
	}

	/**
	 * Creates the appointment and its reminder rows in ONE transaction (a transactional outbox):
	 * the intent to notify is committed atomically with the booking, so there is never an
	 * appointment without reminders or a reminder without an appointment.
	 */
	public CreateResult create(AppointmentDetails details, String idempotencyKey) {
		String requestHash = RequestHasher.hash(details);
		if (idempotencyKey != null) {
			Optional<Appointment> existing = this.appointments
				.findByDealershipIdAndIdempotencyKey(details.dealershipId(), idempotencyKey);
			if (existing.isPresent()) {
				return replay(existing.get(), requestHash);
			}
		}
		Instant now = this.clock.instant();
		validateScheduledAt(details.scheduledAt(), now);
		try {
			long id = this.transactions.execute((status) -> insert(details, idempotencyKey, requestHash, now));
			AppointmentResponse created = get(id);
			log.info("APPOINTMENT_CREATED id={} dealership={} scheduledAt={} reminders={}", id, details.dealershipId(),
					details.scheduledAt(), describe(created));
			return new CreateResult(created, false);
		}
		catch (DataIntegrityViolationException ex) {
			if (idempotencyKey == null) {
				throw ex;
			}
			// A concurrent request with the same key committed first; the unique index stopped this one.
			Appointment winner = this.appointments
				.findByDealershipIdAndIdempotencyKey(details.dealershipId(), idempotencyKey)
				.orElseThrow(() -> ex);
			return replay(winner, requestHash);
		}
	}

	/** The most recent appointments, newest first, with two queries in total (no N+1). */
	public List<AppointmentResponse> recent(int limit) {
		List<Appointment> latest = this.appointments.findByOrderByIdDesc(Limit.of(limit));
		if (latest.isEmpty()) {
			return List.of();
		}
		Map<Long, List<Reminder>> remindersByAppointment = this.reminders
			.findByAppointmentIdInOrderByAppointmentRevisionAscDueAtAsc(latest.stream().map(Appointment::getId).toList())
			.stream()
			.collect(Collectors.groupingBy(Reminder::getAppointmentId));
		return latest.stream()
			.map((appointment) -> AppointmentResponse.from(appointment,
					remindersByAppointment.getOrDefault(appointment.getId(), List.of())))
			.toList();
	}

	public AppointmentResponse get(long id) {
		Appointment appointment = this.appointments.findById(id).orElseThrow(() -> new AppointmentNotFoundException(id));
		return AppointmentResponse.from(appointment,
				this.reminders.findByAppointmentIdOrderByAppointmentRevisionAscDueAtAsc(id));
	}

	/**
	 * Cancels the appointment and, in the same transaction, every reminder still PENDING. A reminder
	 * already PROCESSING is in flight and can't be recalled; the row lock on the reminder decides the
	 * order, so the outcome is either "cancelled, nothing sent" or "sent once", never twice.
	 */
	public AppointmentResponse cancel(long id) {
		Integer cancelled = this.transactions.execute((status) -> {
			Appointment appointment = this.appointments.findById(id)
				.orElseThrow(() -> new AppointmentNotFoundException(id));
			if (appointment.getStatus() == AppointmentStatus.CANCELLED) {
				return null; // cancelling twice is not an error
			}
			Instant now = this.clock.instant();
			if (appointment.hasStarted(now)) {
				throw new AppointmentConflictException("Appointment " + id + " has already started");
			}
			appointment.cancel(now);
			return this.reminders.closeAllPending(id, ReminderStatus.CANCELLED.name(), "appointment cancelled", now);
		});
		if (cancelled != null) {
			log.info("APPOINTMENT_CANCELLED id={} pendingRemindersCancelled={}", id, cancelled);
		}
		return get(id);
	}

	/**
	 * Moves the appointment and starts a new revision: PENDING reminders of the old time become
	 * SUPERSEDED and new ones are planned for the new time. Because the revision is part of a
	 * reminder's identity, "24h before the new time" is a different reminder from "24h before the old time".
	 */
	public AppointmentResponse reschedule(long id, Instant newScheduledAt) {
		Integer revision = this.transactions.execute((status) -> {
			Appointment appointment = this.appointments.findById(id)
				.orElseThrow(() -> new AppointmentNotFoundException(id));
			Instant now = this.clock.instant();
			if (appointment.getStatus() == AppointmentStatus.CANCELLED) {
				throw new AppointmentConflictException("Appointment " + id + " is cancelled");
			}
			if (appointment.hasStarted(now)) {
				throw new AppointmentConflictException("Appointment " + id + " has already started");
			}
			validateScheduledAt(newScheduledAt, now);
			if (newScheduledAt.equals(appointment.getScheduledAt())) {
				return null;
			}
			appointment.reschedule(newScheduledAt, now);
			this.reminders.closeAllPending(id, ReminderStatus.SUPERSEDED.name(), "appointment rescheduled", now);
			this.reminders.saveAll(this.policy.plan(id, appointment.getRevision(), newScheduledAt, now));
			return appointment.getRevision();
		});
		if (revision != null) {
			log.info("APPOINTMENT_RESCHEDULED id={} revision={} scheduledAt={}", id, revision, newScheduledAt);
		}
		return get(id);
	}

	private long insert(AppointmentDetails details, String idempotencyKey, String requestHash, Instant now) {
		// a duplicate key fails right here.
		Appointment appointment = this.appointments.save(Appointment.schedule(details, idempotencyKey, requestHash, now));
		this.reminders.saveAll(this.policy.plan(appointment.getId(), appointment.getRevision(),
				appointment.getScheduledAt(), now));
		return appointment.getId();
	}

	private CreateResult replay(Appointment existing, String requestHash) {
		if (!requestHash.equals(existing.getRequestHash())) {
			throw new BookingRejectedException("Idempotency-Key was already used with a different request");
		}
		log.info("APPOINTMENT_REPLAYED id={} (same Idempotency-Key, nothing created)", existing.getId());
		return new CreateResult(get(existing.getId()), true);
	}

	private void validateScheduledAt(Instant scheduledAt, Instant now) {
		if (!scheduledAt.isAfter(now)) {
			throw new InvalidBookingException("scheduledAt", "must be in the future");
		}
		if (scheduledAt.isAfter(now.plus(this.properties.booking().maxHorizon()))) {
			throw new InvalidBookingException("scheduledAt",
					"must be within " + this.properties.booking().maxHorizon().toDays() + " days");
		}
	}

	private static String describe(AppointmentResponse appointment) {
		return appointment.reminders()
			.stream()
			.map((reminder) -> reminder.type() + " " + reminder.status() + " due " + reminder.dueAt())
			.toList()
			.toString();
	}

}
