package com.dealership.appointmentreminder.dto;

import java.time.OffsetDateTime;

import jakarta.validation.constraints.NotNull;

public record RescheduleAppointmentRequest(@NotNull OffsetDateTime scheduledAt) {

}
