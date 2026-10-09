package com.dealership.appointmentreminder.service;

import com.dealership.appointmentreminder.entity.Channel;

/** Keeps phone numbers and emails out of logs. The database keeps the real values. */
public final class Masking {

	private Masking() {
	}

	public static String mask(Channel channel, String recipient) {
		return (channel == Channel.SMS) ? maskPhone(recipient) : maskEmail(recipient);
	}

	/** {@code +13125550142 -> +1312*****42} */
	public static String maskPhone(String phone) {
		if (phone == null || phone.length() < 8) {
			return "***";
		}
		return phone.substring(0, 5) + "*".repeat(phone.length() - 7) + phone.substring(phone.length() - 2);
	}

	/** {@code alex@example.com -> a***@example.com} */
	public static String maskEmail(String email) {
		int at = (email != null) ? email.indexOf('@') : -1;
		if (at < 1) {
			return "***";
		}
		return email.charAt(0) + "***" + email.substring(at);
	}

}
