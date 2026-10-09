package com.dealership.appointmentreminder.demo;

import java.time.Instant;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;

/** Copies matching log events into {@link ServiceLogBuffer} as they are written. */
class ServiceLogAppender extends AppenderBase<ILoggingEvent> {

	static final String NAME = "serviceLogPanel";

	private final ServiceLogBuffer buffer;

	ServiceLogAppender(ServiceLogBuffer buffer) {
		this.buffer = buffer;
		setName(NAME);
	}

	@Override
	protected void append(ILoggingEvent event) {
		this.buffer.append(Instant.ofEpochMilli(event.getTimeStamp()), event.getLevel().toString(),
				event.getFormattedMessage());
	}

}
