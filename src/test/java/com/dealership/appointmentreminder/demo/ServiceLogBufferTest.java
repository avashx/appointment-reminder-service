package com.dealership.appointmentreminder.demo;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.dealership.appointmentreminder.demo.ServiceLogBuffer.Snapshot;

import static org.assertj.core.api.Assertions.assertThat;

class ServiceLogBufferTest {

	private final ServiceLogBuffer buffer = new ServiceLogBuffer();

	@Test
	void keepsOnlyAppointmentReminderAndNotificationLines() {
		Instant now = Instant.parse("2026-10-09T10:00:00Z");
		this.buffer.append(now, "INFO", "Tomcat started on port 8080");
		this.buffer.append(now, "INFO", "APPOINTMENT_CREATED id=4");
		this.buffer.append(now, "INFO", "NOTIFICATION_STUB_ACCEPTED key=appt-4-r1-REMINDER_24H providerMessageId=stub-1");

		Snapshot snapshot = this.buffer.after(0);

		assertThat(snapshot.reset()).isFalse();
		assertThat(snapshot.lines()).extracting(ServiceLogBuffer.ServiceLogEntry::message)
			.containsExactly("APPOINTMENT_CREATED id=4",
					"NOTIFICATION_STUB_ACCEPTED key=appt-4-r1-REMINDER_24H providerMessageId=stub-1");
	}

	@Test
	void returnsOnlyLinesNewerThanTheCursor() {
		Instant now = Instant.parse("2026-10-09T10:00:00Z");
		this.buffer.append(now, "INFO", "REMINDER_CLAIMED reminderId=7");
		this.buffer.append(now, "INFO", "REMINDER_SENT reminderId=7");

		assertThat(this.buffer.after(1).lines()).extracting(ServiceLogBuffer.ServiceLogEntry::message)
			.containsExactly("REMINDER_SENT reminderId=7");
		assertThat(this.buffer.after(2).lines()).isEmpty();
	}

	@Test
	void resetWhenTheCursorIsAheadOfThisProcess() {
		this.buffer.append(Instant.parse("2026-10-09T10:00:00Z"), "WARN", "REMINDER_RECLAIMED reminderId=7");

		Snapshot snapshot = this.buffer.after(50);

		assertThat(snapshot.reset()).isTrue();
		assertThat(snapshot.lines()).hasSize(1);
	}

}
