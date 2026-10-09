package com.dealership.appointmentreminder.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration(proxyBeanMethods = false)
class SchedulingConfig {

	/**
	 * Bounded pool that runs Steps 3 and 4 (send, then record the outcome) for claimed reminders in
	 * parallel. Each task holds a database connection only briefly, but threads must stay below the
	 * Hikari pool size. On shutdown it finishes in-flight sends instead of abandoning them mid-send.
	 */
	@Bean
	ThreadPoolTaskExecutor reminderSendExecutor(ReminderProperties properties) {
		int threads = properties.worker().threads();
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(threads);
		executor.setMaxPoolSize(threads);
		executor.setQueueCapacity(properties.worker().batchSize());
		// A claimed reminder is never dropped: if the pool is saturated or shutting down, the claiming thread sends it.
		executor.setRejectedExecutionHandler((task, pool) -> task.run());
		executor.setThreadNamePrefix("reminder-send-");
		executor.setWaitForTasksToCompleteOnShutdown(true);
		executor.setAwaitTerminationSeconds(30);
		return executor;
	}

	/** Tests set reminder.worker.enabled=false and drive the ReminderWorker themselves. */
	@Configuration(proxyBeanMethods = false)
	@EnableScheduling
	@ConditionalOnProperty(name = "reminder.worker.enabled", havingValue = "true", matchIfMissing = true)
	static class WorkerSchedule {

	}

}
