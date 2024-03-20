package com.github.ansarhun.resticvolumepopulator.config;

import com.github.ansarhun.resticvolumepopulator.event.PublishResourceEventHandler;
import com.github.ansarhun.resticvolumepopulator.k8s.ResticVolumePopulator;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;
import io.fabric8.kubernetes.client.informers.SharedInformerFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.ContextStoppedEvent;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@Configuration
public class KubernetesConfiguration {
    private static final int NO_RESYNC = 0;
    private static final int RESYNC_PERIOD = 60_000;

    /// region Client

    @Bean
    public KubernetesClient kubernetesClient(
            List<KubernetesClientBuilderCustomizer> builderCustomizerList
    ) {
        KubernetesClientBuilder kubernetesClientBuilder = new KubernetesClientBuilder();

        builderCustomizerList.forEach(customizer -> customizer.customize(kubernetesClientBuilder));

        return kubernetesClientBuilder
                .build();
    }

    /// endregion

    /// region Application event handlers

    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE - 1)
    public ApplicationListener<ApplicationReadyEvent> start(
            SharedInformerFactory sharedInformerFactory
    ) {
        return args -> {
            try {
                sharedInformerFactory
                        .startAllRegisteredInformers()
                        .get(10, TimeUnit.SECONDS);
            } catch (InterruptedException | ExecutionException | TimeoutException e) {
                throw new RuntimeException(e);
            }
            log.info("Shared informers started");
        };
    }

    @Bean
    public ApplicationListener<ContextStoppedEvent> stop(
            SharedInformerFactory sharedInformerFactory
    ) {
        return event -> sharedInformerFactory.stopAllRegisteredInformers();
    }

    /// endregion

    /// region Informers

    @Bean
    public SharedInformerFactory sharedInformerFactory(KubernetesClient client) {
        return client.informers();
    }

    @Bean
    public SharedIndexInformer<Pod> podInformer(
            SharedInformerFactory factory,
            List<ResourceEventHandler<Pod>> eventHandlers
    ) {
        SharedIndexInformer<Pod> sharedIndexInformer =
                factory.sharedIndexInformerFor(Pod.class, NO_RESYNC);
        eventHandlers.forEach(sharedIndexInformer::addEventHandler);
        return sharedIndexInformer;
    }

    @Bean
    public SharedIndexInformer<PersistentVolumeClaim> pvcInformer(
            SharedInformerFactory factory,
            List<ResourceEventHandler<PersistentVolumeClaim>> eventHandlers
    ) {
        SharedIndexInformer<PersistentVolumeClaim> sharedIndexInformer =
                factory.sharedIndexInformerFor(PersistentVolumeClaim.class, RESYNC_PERIOD);
        eventHandlers.forEach(sharedIndexInformer::addEventHandler);

        return sharedIndexInformer;
    }

    @Bean
    public SharedIndexInformer<ResticVolumePopulator> resticVolumePopulatorInformer(
            SharedInformerFactory factory,
            List<ResourceEventHandler<ResticVolumePopulator>> eventHandlers
    ) {
        SharedIndexInformer<ResticVolumePopulator> sharedIndexInformer =
                factory.sharedIndexInformerFor(ResticVolumePopulator.class, NO_RESYNC);
        eventHandlers.forEach(sharedIndexInformer::addEventHandler);
        return sharedIndexInformer;
    }

    /// endregion

    /// region Event handlers

    @Bean
    public ResourceEventHandler<Pod> podEventHandler(
            ApplicationEventPublisher applicationEventPublisher
    ) {
        return new PublishResourceEventHandler<>(applicationEventPublisher);
    }

    @Bean
    public ResourceEventHandler<PersistentVolumeClaim> pvcEventHandler(
            ApplicationEventPublisher applicationEventPublisher
    ) {
        return new PublishResourceEventHandler<>(applicationEventPublisher);
    }

    @Bean
    public ResourceEventHandler<ResticVolumePopulator> resticVolumePopulatorEventHandler(
            ApplicationEventPublisher applicationEventPublisher
    ) {
        return new PublishResourceEventHandler<>(applicationEventPublisher);
    }

    /// endregion

    @FunctionalInterface
    public interface KubernetesClientBuilderCustomizer {

        void customize(KubernetesClientBuilder kubernetesClientBuilder);
    }
}
