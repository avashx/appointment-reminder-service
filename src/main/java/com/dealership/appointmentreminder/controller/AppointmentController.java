package com.dealership.appointmentreminder.controller;

import java.net.URI;
import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.dealership.appointmentreminder.dto.AppointmentResponse;
import com.dealership.appointmentreminder.dto.CreateAppointmentRequest;
import com.dealership.appointmentreminder.dto.RescheduleAppointmentRequest;
import com.dealership.appointmentreminder.exception.InvalidBookingException;
import com.dealership.appointmentreminder.service.AppointmentService;
import com.dealership.appointmentreminder.service.AppointmentService.CreateResult;

@RestController
@RequestMapping("/appointments")
class AppointmentController {

	static final String IDEMPOTENCY_KEY = "Idempotency-Key";

	private final AppointmentService service;

	AppointmentController(AppointmentService service) {
		this.service = service;
	}

	/**
	 * 201 for a new appointment. 200 + "Idempotent-Replayed: true" when the same Idempotency-Key and
	 * body arrive again (a client retry), because nothing new was created.
	 */
	@PostMapping
	ResponseEntity<AppointmentResponse> create(@Valid @RequestBody CreateAppointmentRequest request,
			@RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey) {
		if (idempotencyKey != null && (idempotencyKey.isBlank() || idempotencyKey.length() > 100)) {
			throw new InvalidBookingException(IDEMPOTENCY_KEY, "must be 1 to 100 characters");
		}
		CreateResult result = this.service.create(request.toDetails(), idempotencyKey);
		if (result.replayed()) {
			return ResponseEntity.ok().header("Idempotent-Replayed", "true").body(result.appointment());
		}
		return ResponseEntity.created(URI.create("/appointments/" + result.appointment().id()))
			.body(result.appointment());
	}

	/** Newest first; backs the demo console. */
	@GetMapping
	List<AppointmentResponse> recent(@RequestParam(defaultValue = "20") int limit) {
		return this.service.recent(Math.clamp(limit, 1, 100));
	}

	/** The externally visible proof: per reminder, its status, attempt count, sent time and provider message id. */
	@GetMapping("/{id}")
	AppointmentResponse get(@PathVariable long id) {
		return this.service.get(id);
	}

	@PostMapping("/{id}/cancel")
	AppointmentResponse cancel(@PathVariable long id) {
		return this.service.cancel(id);
	}

	@PatchMapping("/{id}")
	AppointmentResponse reschedule(@PathVariable long id, @Valid @RequestBody RescheduleAppointmentRequest request) {
		return this.service.reschedule(id, request.scheduledAt().toInstant());
	}

}
