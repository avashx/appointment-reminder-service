package com.dealership.appointmentreminder;

import org.springframework.boot.SpringApplication;

/** Runs the application locally against a throwaway MySQL container: {@code ./mvnw spring-boot:test-run}. */
public class TestAppointmentReminderApplication {

	public static void main(String[] args) {
		SpringApplication.from(AppointmentReminderApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
