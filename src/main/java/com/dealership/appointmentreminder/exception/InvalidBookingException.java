package com.dealership.appointmentreminder.exception;

/** A field is well-formed but its value is not acceptable (HTTP 400). */
public class InvalidBookingException extends RuntimeException {

	private final String field;

	public InvalidBookingException(String field, String message) {
		super(message);
		this.field = field;
	}

	public String getField() {
		return this.field;
	}

}
