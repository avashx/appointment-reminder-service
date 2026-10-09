package com.dealership.appointmentreminder.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.dealership.appointmentreminder.demo.ServiceLogBuffer;
import com.dealership.appointmentreminder.demo.ServiceLogBuffer.Snapshot;

/** Recent appointment, reminder, and notification log lines for the demo console. */
@RestController
@RequestMapping("/demo/logs")
class DemoLogController {

	private final ServiceLogBuffer logs;

	DemoLogController(ServiceLogBuffer logs) {
		this.logs = logs;
	}

	@GetMapping
	Snapshot recent(@RequestParam(name = "after", defaultValue = "0") long after) {
		return this.logs.after(after);
	}

}
