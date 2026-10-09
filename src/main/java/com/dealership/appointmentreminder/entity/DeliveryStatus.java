package com.dealership.appointmentreminder.entity;

public enum DeliveryStatus {

	/** Recorded just before the provider call. If it never becomes ACCEPTED, the provider's answer was lost. */
	RESERVED,

	/** The provider acknowledged it and returned a message id. */
	ACCEPTED

}
