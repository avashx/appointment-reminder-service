package com.dealership.appointmentreminder.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

import com.dealership.appointmentreminder.entity.Appointment;

public interface AppointmentRepository extends JpaRepository<Appointment, Long> {

	Optional<Appointment> findByDealershipIdAndIdempotencyKey(long dealershipId, String idempotencyKey);

	List<Appointment> findByOrderByIdDesc(Limit limit);

}
