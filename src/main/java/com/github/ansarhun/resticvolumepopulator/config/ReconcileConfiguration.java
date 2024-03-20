package com.github.ansarhun.resticvolumepopulator.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class ReconcileConfiguration {

    @Bean
    public ExecutorService reconcileTaskExecutor() {
        return Executors.newSingleThreadExecutor();
    }

}
