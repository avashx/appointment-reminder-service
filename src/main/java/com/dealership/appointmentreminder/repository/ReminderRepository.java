package com.dealership.appointmentreminder.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.dealership.appointmentreminder.entity.Reminder;

/**
 * The reminder table is also the work queue. Every state transition is one conditional UPDATE:
 * "change X to Y only if it is still X". The returned row count says whether this caller won.
 * Transitions out of PROCESSING are also fenced by the claim token, so a worker whose lease expired
 * can't overwrite the state written by the worker that reclaimed the row.
 * The methods are @Transactional so they work alone; inside a TransactionTemplate they join it.
 */
public interface ReminderRepository extends JpaRepository<Reminder, Long> {

	List<Reminder> findByAppointmentIdOrderByAppointmentRevisionAscDueAtAsc(long appointmentId);

	List<Reminder> findByAppointmentIdInOrderByAppointmentRevisionAscDueAtAsc(Collection<Long> appointmentIds);

	/**
	 * Step 2, CLAIM: locks up to {@code limit} due reminders until the calling transaction ends.
	 * SKIP LOCKED makes a concurrent claimer step over rows another transaction holds instead of
	 * waiting for them, so workers on any number of instances get disjoint batches and never block
	 * each other. A range scan on ix_reminder_poll. Useless without a transaction, hence MANDATORY.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	@Query(value = """
			SELECT * FROM reminder
			 WHERE status = 'PENDING' AND next_attempt_at <= :now
			 ORDER BY next_attempt_at
			 LIMIT :limit
			 FOR UPDATE SKIP LOCKED
			""", nativeQuery = true)
	List<Reminder> lockDueBatch(@Param("now") Instant now, @Param("limit") int limit);

	/** Step 2: PENDING -> PROCESSING for a row this transaction has locked. Every claim counts as an attempt. */
	@Modifying
	@Transactional
	@Query(value = """
			UPDATE reminder
			   SET status = 'PROCESSING', claim_token = :token, claimed_by = :instance, claimed_at = :now,
			       processing_until = :leaseUntil, attempt_count = attempt_count + 1, updated_at = :now
			 WHERE id = :id AND status = 'PENDING'
			""", nativeQuery = true)
	int markProcessing(@Param("id") long id, @Param("token") String token, @Param("instance") String instance,
			@Param("leaseUntil") Instant leaseUntil, @Param("now") Instant now);

	/** PENDING -> a status in which nothing is sent (CANCELLED, EXPIRED, FAILED). */
	@Modifying
	@Transactional
	@Query(value = """
			UPDATE reminder SET status = :to, status_reason = LEFT(:reason, 255), updated_at = :now
			 WHERE id = :id AND status = 'PENDING'
			""", nativeQuery = true)
	int closePending(@Param("id") long id, @Param("to") String to, @Param("reason") String reason,
			@Param("now") Instant now);

	/** Every PENDING reminder of an appointment -> CANCELLED or SUPERSEDED. A PROCESSING one is already in flight. */
	@Modifying
	@Transactional
	@Query(value = """
			UPDATE reminder SET status = :to, status_reason = LEFT(:reason, 255), updated_at = :now
			 WHERE appointment_id = :appointmentId AND status = 'PENDING'
			""", nativeQuery = true)
	int closeAllPending(@Param("appointmentId") long appointmentId, @Param("to") String to,
			@Param("reason") String reason, @Param("now") Instant now);

	/** Step 1, RECLAIM candidates: PROCESSING rows whose lease has run out. A range scan on ix_reminder_lease. */
	@Query(value = """
			SELECT * FROM reminder
			 WHERE status = 'PROCESSING' AND processing_until < :now
			 ORDER BY processing_until
			 LIMIT :limit
			""", nativeQuery = true)
	List<Reminder> findExpiredLeases(@Param("now") Instant now, @Param("limit") int limit);

	/**
	 * Step 1, RECLAIM: PROCESSING -> PENDING, fenced by the claim token, so it releases exactly the
	 * expired claim the caller saw and never a newer one. Safe to run on every instance at once.
	 */
	@Modifying
	@Transactional
	@Query(value = """
			UPDATE reminder
			   SET status = 'PENDING', claim_token = NULL, processing_until = NULL, next_attempt_at = :now,
			       status_reason = 'lease expired before an outcome was recorded; reclaimed', updated_at = :now
			 WHERE id = :id AND claim_token = :token AND status = 'PROCESSING' AND processing_until < :now
			""", nativeQuery = true)
	int reclaim(@Param("id") long id, @Param("token") String token, @Param("now") Instant now);

	/** Step 4: PROCESSING -> SENT. */
	@Modifying
	@Transactional
	@Query(value = """
			UPDATE reminder
			   SET status = 'SENT', sent_at = :now, provider_message_id = :providerMessageId,
			       status_reason = LEFT(:reason, 255), claim_token = NULL, processing_until = NULL, updated_at = :now
			 WHERE id = :id AND claim_token = :token AND status = 'PROCESSING'
			""", nativeQuery = true)
	int markSent(@Param("id") long id, @Param("token") String token,
			@Param("providerMessageId") String providerMessageId, @Param("reason") String reason,
			@Param("now") Instant now);

	/** Step 4: PROCESSING -> PENDING, to be claimed again at {@code retryAt}. */
	@Modifying
	@Transactional
	@Query(value = """
			UPDATE reminder
			   SET status = 'PENDING', next_attempt_at = :retryAt, status_reason = LEFT(:reason, 255),
			       claim_token = NULL, processing_until = NULL, updated_at = :now
			 WHERE id = :id AND claim_token = :token AND status = 'PROCESSING'
			""", nativeQuery = true)
	int releaseForRetry(@Param("id") long id, @Param("token") String token, @Param("retryAt") Instant retryAt,
			@Param("reason") String reason, @Param("now") Instant now);

	/** Step 4: PROCESSING -> FAILED. */
	@Modifying
	@Transactional
	@Query(value = """
			UPDATE reminder
			   SET status = 'FAILED', status_reason = LEFT(:reason, 255), claim_token = NULL,
			       processing_until = NULL, updated_at = :now
			 WHERE id = :id AND claim_token = :token AND status = 'PROCESSING'
			""", nativeQuery = true)
	int markFailed(@Param("id") long id, @Param("token") String token, @Param("reason") String reason,
			@Param("now") Instant now);

}
