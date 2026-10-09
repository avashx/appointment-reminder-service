package com.dealership.appointmentreminder.exception;

/** The request is well-formed but can't be processed, e.g. an Idempotency-Key reused with a different body (HTTP 422). */
public class BookingRejectedException extends RuntimeException {

	public BookingRejectedException(String message) {
		super(message);
	}

}
