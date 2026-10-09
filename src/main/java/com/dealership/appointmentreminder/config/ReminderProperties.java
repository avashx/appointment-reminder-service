package com.dealership.appointmentreminder.config;

import java.time.Duration;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import com.dealership.appointmentreminder.entity.ReminderType;

/**
 * Typed view of the {@code reminder.*} settings. Validated at startup, so a typo in the YAML
 * fails the boot instead of silently scheduling reminders at the wrong time.
 */
@Validated
@ConfigurationProperties("reminder")
public record ReminderProperties(
		@NotEmpty Map<ReminderType, TypeSettings> types,
		@NotNull @Valid Worker worker,
		@NotNull @Valid Booking booking) {

	public ReminderProperties {
		for (ReminderType type : ReminderType.values()) {
			if (types == null || !types.containsKey(type)) {
				throw new IllegalArgumentException("Missing reminder.types." + type + " configuration");
			}
		}
	}

	public TypeSettings settingsFor(ReminderType type) {
		return this.types.get(type);
	}

	/** {@code offset}: how long before the appointment it is due. {@code maxLateness}: how late it may still go out. */
	public record TypeSettings(@NotNull Duration offset, @NotNull Duration maxLateness) {
	}

	/**
	 * {@code processingLease}: how long a claimed reminder stays PROCESSING before any worker may
	 * reclaim it. It must be longer than the provider's call timeout, so a live worker always
	 * finishes (or times out) before its lease runs out.
	 */
	public record Worker(boolean enabled, @NotNull Duration pollInterval, @Positive int batchSize,
			@Positive int threads, @NotNull Duration processingLease, @Positive int maxAttempts,
			@NotNull Duration backoffInitial, @NotNull Duration backoffMax) {
	}

	public record Booking(@NotNull Duration maxHorizon) {
	}

}
