package com.github.ansarhun.resticvolumepopulator.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "application")
public class ApplicationProperties {
    private boolean bootstrapCrds;
}
