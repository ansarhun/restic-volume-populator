package com.github.ansarhun.resticvolumepopulator.component;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.Updatable;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Data
@Slf4j
@Component
@ConditionalOnProperty(prefix = "application", name = "bootstrap-crds")
public class CRDBootstrap {

    private final KubernetesClient kubernetesClient;

    @PostConstruct
    public void loadCRD() {
        log.info("Loading CustomResourceDefinition for resticvolumepopulator");

        kubernetesClient
                .apiextensions()
                .v1()
                .customResourceDefinitions()
                .load(
                        getClass()
                                .getResourceAsStream("/META-INF/fabric8/resticvolumepopulators.ansarhun.github.com-v1.yml")
                )
                .createOr(Updatable::update);
    }
}
