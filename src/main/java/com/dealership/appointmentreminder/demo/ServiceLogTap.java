package com.dealership.appointmentreminder.demo;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;

/**
 * Attaches {@link ServiceLogAppender} to the root logger so the console can show the lines that
 * otherwise only exist in the process log.
 */
@Component
class ServiceLogTap {

	private final ServiceLogBuffer buffer;

	private ServiceLogAppender appender;

	ServiceLogTap(ServiceLogBuffer buffer) {
		this.buffer = buffer;
	}

	@PostConstruct
	void attach() {
		if (!(LoggerFactory.getILoggerFactory() instanceof LoggerContext context)) {
			return;
		}
		Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);
		if (root.getAppender(ServiceLogAppender.NAME) != null) {
			return;
		}
		ServiceLogAppender created = new ServiceLogAppender(this.buffer);
		created.setContext(context);
		created.start();
		root.addAppender(created);
		this.appender = created;
	}

	@PreDestroy
	void detach() {
		if (this.appender == null) {
			return;
		}
		if (LoggerFactory.getILoggerFactory() instanceof LoggerContext context) {
			context.getLogger(Logger.ROOT_LOGGER_NAME).detachAppender(this.appender);
		}
		this.appender.stop();
	}

}
