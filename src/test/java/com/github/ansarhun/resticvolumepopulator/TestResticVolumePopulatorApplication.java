package com.github.ansarhun.resticvolumepopulator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;

@TestConfiguration(proxyBeanMethods = false)
public class TestResticVolumePopulatorApplication {

	public static void main(String[] args) {
		SpringApplication.from(ResticVolumePopulatorApplication::main).with(TestResticVolumePopulatorApplication.class).run(args);
	}

}
