package com.dealership.appointmentreminder.exception;

/** The appointment's current state doesn't allow the change, e.g. cancelling one that has started (HTTP 409). */
public class AppointmentConflictException extends RuntimeException {

	public AppointmentConflictException(String message) {
		super(message);
	}

}
