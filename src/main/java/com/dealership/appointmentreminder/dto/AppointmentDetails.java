package com.dealership.appointmentreminder.dto;

import java.time.Instant;

import com.dealership.appointmentreminder.entity.Channel;

/**
 * A validated, normalized booking request: what the API hands to {@code AppointmentService}.
 * {@code scheduledAt} is an Instant, so "10:00-05:00" and "15:00Z" are the same appointment.
 */
public record AppointmentDetails(long dealershipId, String customerName, String customerPhone, String customerEmail,
		Channel preferredChannel, String vehicle, String serviceType, Instant scheduledAt) {

}
