package com.dealership.appointmentreminder;

import java.util.TimeZone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class AppointmentReminderApplication {

	public static void main(String[] args) {
		// Every persisted time is an Instant and Hibernate writes UTC, so this is belt and braces:
		// a stray LocalDateTime.now() or Date.toString() can't silently pick up the host's zone.
		TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
		SpringApplication.run(AppointmentReminderApplication.class, args);
	}

}
