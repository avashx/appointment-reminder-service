package com.dealership.appointmentreminder.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/** A clock the test controls: "wait 24 hours" becomes {@code clock.advance(Duration.ofHours(24))}. */
public class MutableClock extends Clock {

	private final AtomicReference<Instant> now;

	private final Instant start;

	public MutableClock(Instant start) {
		this.start = start;
		this.now = new AtomicReference<>(start);
	}

	@Override
	public Instant instant() {
		return this.now.get();
	}

	@Override
	public ZoneId getZone() {
		return ZoneOffset.UTC;
	}

	@Override
	public Clock withZone(ZoneId zone) {
		throw new UnsupportedOperationException("The application only uses UTC instants");
	}

	public void setInstant(Instant instant) {
		this.now.set(instant);
	}

	public void advance(Duration duration) {
		this.now.updateAndGet((instant) -> instant.plus(duration));
	}

	public void reset() {
		this.now.set(this.start);
	}

}
