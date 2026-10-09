package com.dealership.appointmentreminder.demo;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class ServiceLogBufferConfiguration {

	@Bean
	ServiceLogBuffer serviceLogBuffer() {
		return new ServiceLogBuffer();
	}

}
