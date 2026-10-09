package com.dealership.appointmentreminder.exception;

public class AppointmentNotFoundException extends RuntimeException {

	public AppointmentNotFoundException(long id) {
		super("Appointment " + id + " not found");
	}

}
