package com.vnkst.closing;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ClosingApplication {

	public static void main(String[] args) {
		SpringApplication.run(ClosingApplication.class, args);
	}

}
