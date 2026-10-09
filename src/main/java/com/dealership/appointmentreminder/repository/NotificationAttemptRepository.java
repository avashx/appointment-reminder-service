package com.dealership.appointmentreminder.repository;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.dealership.appointmentreminder.entity.NotificationAttempt;

public interface NotificationAttemptRepository extends JpaRepository<NotificationAttempt, Long> {

	List<NotificationAttempt> findByReminderIdOrderByAttemptNoAsc(long reminderId);

	/**
	 * Step 4: records what happened to the attempt made under {@code token}. A late answer from a
	 * worker whose lease expired may still replace ABANDONED, so the audit says what really happened.
	 */
	@Modifying
	@Transactional
	@Query(value = """
			UPDATE notification_attempt
			   SET outcome = :outcome, provider_message_id = :providerMessageId, error = LEFT(:error, 500),
			       completed_at = :now
			 WHERE reminder_id = :reminderId AND claim_token = :token AND outcome IN ('STARTED', 'ABANDONED')
			""", nativeQuery = true)
	int complete(@Param("reminderId") long reminderId, @Param("token") String token, @Param("outcome") String outcome,
			@Param("providerMessageId") String providerMessageId, @Param("error") String error,
			@Param("now") Instant now);

	/** Step 1: the worker holding {@code token} never reported back before its lease expired. */
	@Modifying
	@Transactional
	@Query(value = """
			UPDATE notification_attempt
			   SET outcome = 'ABANDONED', error = 'lease expired before an outcome was recorded', completed_at = :now
			 WHERE reminder_id = :reminderId AND claim_token = :token AND outcome = 'STARTED'
			""", nativeQuery = true)
	int markAbandoned(@Param("reminderId") long reminderId, @Param("token") String token, @Param("now") Instant now);

}
