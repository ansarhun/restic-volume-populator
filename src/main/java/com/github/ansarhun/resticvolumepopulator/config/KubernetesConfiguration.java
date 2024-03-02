package com.github.ansarhun.resticvolumepopulator.config;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class KubernetesConfiguration {

    @Bean
    public KubernetesClient kubernetesClient(
            List<KubernetesClientBuilderCustomizer> builderCustomizerList
    ) {
        KubernetesClientBuilder kubernetesClientBuilder = new KubernetesClientBuilder();

        builderCustomizerList.forEach(customizer -> customizer.customize(kubernetesClientBuilder));

        return kubernetesClientBuilder
                .build();
    }

    @FunctionalInterface
    public interface KubernetesClientBuilderCustomizer {

        void customize(KubernetesClientBuilder kubernetesClientBuilder);
    }
}
