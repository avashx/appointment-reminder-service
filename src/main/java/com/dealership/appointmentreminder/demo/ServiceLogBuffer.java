package com.dealership.appointmentreminder.demo;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The lines the demo console shows. Same filter as {@code scripts/demo.sh logs}: appointment,
 * reminder, and notification events. Everything else the process logs stays off the page.
 */
public class ServiceLogBuffer {

	static final Pattern INTERESTING = Pattern.compile("APPOINTMENT_|REMINDER_|NOTIFICATION_");

	private static final int CAPACITY = 200;

	private final ArrayDeque<ServiceLogEntry> lines = new ArrayDeque<>();

	private long nextId = 1;

	public void append(Instant at, String level, String message) {
		if (message == null || !INTERESTING.matcher(message).find()) {
			return;
		}
		synchronized (this.lines) {
			this.lines.addLast(new ServiceLogEntry(this.nextId++, at.toString(), level, message));
			while (this.lines.size() > CAPACITY) {
				this.lines.removeFirst();
			}
		}
	}

	/** Lines newer than {@code afterId}. {@code reset} means this process restarted and ids began again. */
	public Snapshot after(long afterId) {
		synchronized (this.lines) {
			if (this.lines.isEmpty()) {
				return new Snapshot(afterId > 0, List.of());
			}
			long newest = this.lines.peekLast().id();
			boolean reset = afterId > newest;
			long cursor = reset ? 0 : afterId;
			List<ServiceLogEntry> page = new ArrayList<>();
			for (ServiceLogEntry line : this.lines) {
				if (line.id() > cursor) {
					page.add(line);
				}
			}
			return new Snapshot(reset, List.copyOf(page));
		}
	}

	public record ServiceLogEntry(long id, String at, String level, String message) {
	}

	public record Snapshot(boolean reset, List<ServiceLogEntry> lines) {
	}

}
