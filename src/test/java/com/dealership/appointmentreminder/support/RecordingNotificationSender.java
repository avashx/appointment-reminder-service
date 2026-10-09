package com.dealership.appointmentreminder.support;

import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.dealership.appointmentreminder.dto.Notification;
import com.dealership.appointmentreminder.service.NotificationSender;
import com.dealership.appointmentreminder.service.SendResult;

/**
 * Test double for the provider, sitting behind the real {@code IdempotentNotificationSender}. It
 * records every call that reached the "provider" and can be scripted to reject, fail ambiguously,
 * block, or crash the calling worker. {@link #keysPossiblyDeliveredMoreThanOnce()} is the
 * customer's-eye view of the guarantee.
 */
public class RecordingNotificationSender implements NotificationSender {

	/** Thrown to simulate the process dying at that exact line. An Error, so no catch (RuntimeException) can swallow it. */
	public static class SimulatedCrash extends Error {

		public SimulatedCrash() {
			super("simulated process crash");
		}

	}

	public enum Outcome {

		ACCEPTED, RETRYABLE_REJECTION, PERMANENT_REJECTION, UNKNOWN, CRASHED_AFTER_DELIVERY, THREW_AFTER_DELIVERY;

		boolean possiblyDelivered() {
			return this != RETRYABLE_REJECTION && this != PERMANENT_REJECTION;
		}

	}

	public record Call(String idempotencyKey, Outcome outcome, Notification notification) {
	}

	private final Queue<Call> calls = new ConcurrentLinkedQueue<>();

	private final ConcurrentLinkedDeque<Function<Notification, SendResult>> script = new ConcurrentLinkedDeque<>();

	@Override
	public SendResult send(Notification notification) {
		Function<Notification, SendResult> step = this.script.pollFirst();
		return (step != null) ? step.apply(notification) : accept(notification);
	}

	// ---- scripting: each call consumes the next step; with no steps left, calls are accepted ----

	public RecordingNotificationSender thenRetryableRejection(String reason) {
		return then((n) -> {
			record(n, Outcome.RETRYABLE_REJECTION);
			return new SendResult.RetryableRejection(reason);
		});
	}

	public RecordingNotificationSender thenPermanentRejection(String reason) {
		return then((n) -> {
			record(n, Outcome.PERMANENT_REJECTION);
			return new SendResult.PermanentRejection(reason);
		});
	}

	/** The provider got the request, but its answer is unknown (e.g. a 502 from a gateway after forwarding it). */
	public RecordingNotificationSender thenUnknown(String reason) {
		return then((n) -> {
			record(n, Outcome.UNKNOWN);
			return new SendResult.Unknown(reason);
		});
	}

	/** The provider got the request, then the call failed ambiguously (e.g. a read timeout). */
	public RecordingNotificationSender thenThrowAfterDelivery(RuntimeException exception) {
		return then((n) -> {
			record(n, Outcome.THREW_AFTER_DELIVERY);
			throw exception;
		});
	}

	/** The provider accepted the message, then this process died before recording the outcome. */
	public RecordingNotificationSender thenCrashAfterDelivery() {
		return then((n) -> {
			record(n, Outcome.CRASHED_AFTER_DELIVERY);
			throw new SimulatedCrash();
		});
	}

	/** This process died inside the provider call, before the request left the machine. */
	public RecordingNotificationSender thenCrashBeforeDelivery() {
		return then((n) -> {
			throw new SimulatedCrash();
		});
	}

	/** Signals {@code entered}, then blocks until {@code release} before accepting: a slow or paused worker. */
	public RecordingNotificationSender thenBlockThenAccept(CountDownLatch entered, CountDownLatch release) {
		return then((n) -> {
			entered.countDown();
			try {
				if (!release.await(30, TimeUnit.SECONDS)) {
					throw new IllegalStateException("test never released the blocked send");
				}
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException(ex);
			}
			return accept(n);
		});
	}

	// ---- assertions ----

	public List<Call> calls() {
		return List.copyOf(this.calls);
	}

	public long possibleDeliveriesOf(String idempotencyKey) {
		return this.calls.stream()
			.filter((call) -> call.idempotencyKey().equals(idempotencyKey) && call.outcome().possiblyDelivered())
			.count();
	}

	public long totalPossibleDeliveries() {
		return this.calls.stream().filter((call) -> call.outcome().possiblyDelivered()).count();
	}

	/** Must always be empty: no customer may have received the same reminder twice. */
	public Map<String, Long> keysPossiblyDeliveredMoreThanOnce() {
		return this.calls.stream()
			.filter((call) -> call.outcome().possiblyDelivered())
			.collect(Collectors.groupingBy(Call::idempotencyKey, Collectors.counting()))
			.entrySet()
			.stream()
			.filter((entry) -> entry.getValue() > 1)
			.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
	}

	public void reset() {
		this.calls.clear();
		this.script.clear();
	}

	private RecordingNotificationSender then(Function<Notification, SendResult> step) {
		this.script.addLast(step);
		return this;
	}

	private SendResult accept(Notification notification) {
		record(notification, Outcome.ACCEPTED);
		return new SendResult.Accepted("test-" + UUID.randomUUID());
	}

	private void record(Notification notification, Outcome outcome) {
		this.calls.add(new Call(notification.idempotencyKey(), outcome, notification));
	}

}
