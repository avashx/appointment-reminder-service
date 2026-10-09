package com.dealership.appointmentreminder.controller;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.dealership.appointmentreminder.entity.Reminder;
import com.dealership.appointmentreminder.entity.ReminderType;
import com.dealership.appointmentreminder.service.AppointmentService.CreateResult;
import com.dealership.appointmentreminder.support.IntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The HTTP contract: validation, idempotent creation, the lifecycle endpoints, and the proof surface. */
class AppointmentApiIT extends IntegrationTest {

	@Autowired
	private WebApplicationContext context;

	private MockMvc mvc;

	@BeforeEach
	void setUpMockMvc() {
		this.mvc = MockMvcBuilders.webAppContextSetup(this.context).build();
	}

	@Test
	void createsAnAppointmentAndPlansBothRemindersInOneTransaction() throws Exception {
		String json = this.mvc
			.perform(post("/appointments").contentType(MediaType.APPLICATION_JSON).content(body("2026-10-09T10:00:00-05:00")))
			.andExpect(status().isCreated())
			.andExpect(header().string("Location", matchesPattern("/appointments/\\d+")))
			.andExpect(jsonPath("$.status").value("SCHEDULED"))
			.andExpect(jsonPath("$.dealershipId").value(1))
			.andExpect(jsonPath("$.scheduledAt").value("2026-10-09T15:00:00Z"))
			.andExpect(jsonPath("$.reminders.length()").value(2))
			.andExpect(jsonPath("$.reminders[0].type").value("REMINDER_24H"))
			.andExpect(jsonPath("$.reminders[0].status").value("PENDING"))
			.andExpect(jsonPath("$.reminders[0].dueAt").value("2026-10-08T15:00:00Z"))
			.andExpect(jsonPath("$.reminders[0].attemptCount").value(0))
			.andExpect(jsonPath("$.reminders[1].type").value("REMINDER_2H"))
			.andExpect(jsonPath("$.reminders[1].dueAt").value("2026-10-09T13:00:00Z"))
			.andReturn().getResponse().getContentAsString();
		long id = idOf(json);

		this.mvc.perform(get("/appointments/{id}", id))
			.andExpect(jsonPath("$.reminders[0].idempotencyKey").value("appt-" + id + "-r1-REMINDER_24H"))
			.andExpect(jsonPath("$.reminders[1].idempotencyKey").value("appt-" + id + "-r1-REMINDER_2H"));
		assertThat(countRows("appointment")).isEqualTo(1);
		assertThat(countRows("reminder")).isEqualTo(2);
	}

	@Test
	void bookingLessThanADayAheadSkipsTheDayBeforeReminder() throws Exception {
		this.mvc.perform(post("/appointments").contentType(MediaType.APPLICATION_JSON).content(body("2026-10-06T14:00:00Z")))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.reminders[0].type").value("REMINDER_24H"))
			.andExpect(jsonPath("$.reminders[0].status").value("SKIPPED"))
			.andExpect(jsonPath("$.reminders[0].statusReason").value("WINDOW_PASSED_AT_BOOKING"))
			.andExpect(jsonPath("$.reminders[1].status").value("PENDING"));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("invalidRequests")
	void rejectsInvalidRequestsWithFieldErrors(String description, String json, String field) throws Exception {
		this.mvc.perform(post("/appointments").contentType(MediaType.APPLICATION_JSON).content(json))
			.andExpect(status().isBadRequest())
			.andExpect(header().string("Content-Type", "application/problem+json"))
			.andExpect(jsonPath("$.errors['" + field + "']").exists());
		assertThat(countRows("appointment")).isZero();
	}

	static Stream<Arguments> invalidRequests() {
		return Stream.of(
				Arguments.of("no dealership", """
						{"customer":{"name":"Alex","phone":"+13125550142"},"scheduledAt":"2026-10-09T10:00:00Z"}""",
						"dealershipId"),
				Arguments.of("dealership id not positive", body("2026-10-09T10:00:00Z").replace("\"dealershipId\":1", "\"dealershipId\":0"),
						"dealershipId"),
				Arguments.of("no phone and no email", """
						{"dealershipId":1,"customer":{"name":"Alex"},"scheduledAt":"2026-10-09T10:00:00Z"}""",
						"customer.contactPresent"),
				Arguments.of("phone not in E.164", """
						{"dealershipId":1,"customer":{"name":"Alex","phone":"312-555-0142"},"scheduledAt":"2026-10-09T10:00:00Z"}""",
						"customer.phone"),
				Arguments.of("SMS chosen but no phone", """
						{"dealershipId":1,"customer":{"name":"Alex","email":"a@example.com","preferredChannel":"SMS"},
						 "scheduledAt":"2026-10-09T10:00:00Z"}""", "customer.preferredChannelUsable"),
				Arguments.of("missing scheduledAt", """
						{"dealershipId":1,"customer":{"name":"Alex","phone":"+13125550142"}}""", "scheduledAt"),
				Arguments.of("time in the past", body("2026-10-01T10:00:00Z"), "scheduledAt"),
				Arguments.of("more than a year ahead", body("2028-01-01T10:00:00Z"), "scheduledAt"));
	}

	@Test
	void timeWithoutAnOffsetIsRejectedBecauseItIsAmbiguous() throws Exception {
		this.mvc.perform(post("/appointments").contentType(MediaType.APPLICATION_JSON).content(body("2026-10-09T10:00:00")))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.detail").value(containsString("with an offset")));
	}

	@Test
	void anOversizedIdempotencyKeyIsRejected() throws Exception {
		this.mvc.perform(post("/appointments").header("Idempotency-Key", "k".repeat(101))
			.contentType(MediaType.APPLICATION_JSON).content(body("2026-10-09T10:00:00Z")))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors['Idempotency-Key']").exists());
	}

	@Test
	void sameIdempotencyKeyReplaysInsteadOfCreatingAgain() throws Exception {
		String first = this.mvc.perform(post("/appointments").header("Idempotency-Key", "booking-123")
			.contentType(MediaType.APPLICATION_JSON).content(body("2026-10-09T10:00:00-05:00")))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();

		this.mvc.perform(post("/appointments").header("Idempotency-Key", "booking-123")
			.contentType(MediaType.APPLICATION_JSON).content(body("2026-10-09T15:00:00Z"))) // same instant, other offset
			.andExpect(status().isOk())
			.andExpect(header().string("Idempotent-Replayed", "true"))
			.andExpect(jsonPath("$.id").value(idOf(first)));

		assertThat(countRows("appointment")).isEqualTo(1);
		assertThat(countRows("reminder")).isEqualTo(2);
	}

	@Test
	void reusingAKeyForADifferentRequestIsRejected() throws Exception {
		this.mvc.perform(post("/appointments").header("Idempotency-Key", "booking-123")
			.contentType(MediaType.APPLICATION_JSON).content(body("2026-10-09T10:00:00Z")))
			.andExpect(status().isCreated());

		this.mvc.perform(post("/appointments").header("Idempotency-Key", "booking-123")
			.contentType(MediaType.APPLICATION_JSON).content(body("2026-10-10T10:00:00Z")))
			.andExpect(status().isUnprocessableContent());
		assertThat(countRows("appointment")).isEqualTo(1);
	}

	@Test
	void theSameKeyFromAnotherDealershipIsADifferentBooking() throws Exception {
		this.mvc.perform(post("/appointments").header("Idempotency-Key", "booking-123")
			.contentType(MediaType.APPLICATION_JSON).content(body("2026-10-09T10:00:00Z")))
			.andExpect(status().isCreated());
		this.mvc.perform(post("/appointments").header("Idempotency-Key", "booking-123")
			.contentType(MediaType.APPLICATION_JSON)
			.content(body("2026-10-09T10:00:00Z").replace("\"dealershipId\":1", "\"dealershipId\":2")))
			.andExpect(status().isCreated());

		assertThat(countRows("appointment")).isEqualTo(2);
	}

	@Test
	void concurrentRetriesWithTheSameKeyCreateExactlyOneAppointment() throws Exception {
		List<CreateResult> outcomes = Collections.synchronizedList(new ArrayList<>());

		runConcurrently(16, (thread) -> outcomes
			.add(this.appointmentService.create(details(T0.plus(Duration.ofDays(3))), "same-key")));

		assertThat(outcomes).filteredOn((outcome) -> !outcome.replayed()).hasSize(1);
		assertThat(outcomes).extracting((outcome) -> outcome.appointment().id())
			.containsOnly(outcomes.get(0).appointment().id());
		assertThat(countRows("appointment")).isEqualTo(1);
		assertThat(countRows("reminder")).isEqualTo(2);
	}

	@Test
	void getCancelAndRescheduleThroughTheApi() throws Exception {
		String created = this.mvc.perform(post("/appointments").contentType(MediaType.APPLICATION_JSON)
			.content(body("2026-10-09T10:00:00Z"))).andReturn().getResponse().getContentAsString();
		long id = idOf(created);

		this.mvc.perform(get("/appointments/{id}", id)).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));

		this.mvc.perform(patch("/appointments/{id}", id).contentType(MediaType.APPLICATION_JSON)
			.content("{\"scheduledAt\":\"2026-10-12T10:00:00Z\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.revision").value(2))
			.andExpect(jsonPath("$.reminders.length()").value(4))
			.andExpect(jsonPath("$.reminders[0].status").value("SUPERSEDED"))
			.andExpect(jsonPath("$.reminders[2].status").value("PENDING"))
			.andExpect(jsonPath("$.reminders[2].idempotencyKey").value("appt-" + id + "-r2-REMINDER_24H"));

		this.mvc.perform(post("/appointments/{id}/cancel", id))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("CANCELLED"))
			.andExpect(jsonPath("$.reminders[2].status").value("CANCELLED"));
		this.mvc.perform(post("/appointments/{id}/cancel", id)).andExpect(status().isOk());

		this.mvc.perform(patch("/appointments/{id}", id).contentType(MediaType.APPLICATION_JSON)
			.content("{\"scheduledAt\":\"2026-10-13T10:00:00Z\"}"))
			.andExpect(status().isConflict());
	}

	@Test
	void anAppointmentThatHasStartedCannotBeCancelled() throws Exception {
		long id = book(Duration.ofHours(5)).id();
		this.clock.advance(Duration.ofHours(5));

		this.mvc.perform(post("/appointments/{id}/cancel", id)).andExpect(status().isConflict());
	}

	@Test
	void unknownAppointmentIsNotFound() throws Exception {
		this.mvc.perform(get("/appointments/{id}", 424242))
			.andExpect(status().isNotFound())
			.andExpect(header().string("Content-Type", "application/problem+json"));
	}

	@Test
	void getShowsTheProofOfDeliveryForEachReminder() throws Exception {
		Reminder dayBefore = reminder(book(Duration.ofDays(3)), ReminderType.REMINDER_24H);
		this.clock.setInstant(dayBefore.getDueAt());
		this.worker.runOnce();

		this.mvc.perform(get("/appointments/{id}", dayBefore.getAppointmentId()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.reminders[0].status").value("SENT"))
			.andExpect(jsonPath("$.reminders[0].sentAt").value(dayBefore.getDueAt().toString()))
			.andExpect(jsonPath("$.reminders[0].attemptCount").value(1))
			.andExpect(jsonPath("$.reminders[0].providerMessageId").value(startsWith("test-")))
			.andExpect(jsonPath("$.reminders[1].status").value("PENDING"));
	}

	@Test
	void listsRecentAppointmentsNewestFirst() throws Exception {
		long older = book(Duration.ofDays(3)).id();
		long newer = book(Duration.ofDays(4)).id();

		this.mvc.perform(get("/appointments").param("limit", "10"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(2))
			.andExpect(jsonPath("$[0].id").value(newer))
			.andExpect(jsonPath("$[1].id").value(older))
			.andExpect(jsonPath("$[1].vehicle").value("2021 Honda Civic"))
			.andExpect(jsonPath("$[1].reminders.length()").value(2));
	}

	@Test
	void theConsoleIsServedAtTheRoot() throws Exception {
		this.mvc.perform(get("/index.html")).andExpect(status().isOk());
		this.mvc.perform(get("/console.js")).andExpect(status().isOk());
	}

	private static String body(String scheduledAt) {
		return """
				{"dealershipId":1,
				 "customer":{"name":"Alex Doe","phone":"+13125550142","preferredChannel":"SMS"},
				 "vehicle":"2021 Honda Civic","serviceType":"Oil change","scheduledAt":"%s"}""".formatted(scheduledAt);
	}

	private static long idOf(String json) {
		return Long.parseLong(json.replaceAll("(?s).*?\"id\":(\\d+).*", "$1"));
	}

	private int countRows(String table) {
		return this.jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
	}

}
