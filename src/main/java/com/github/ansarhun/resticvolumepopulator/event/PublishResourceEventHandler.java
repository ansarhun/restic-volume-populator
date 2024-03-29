package com.github.ansarhun.resticvolumepopulator.event;

import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;

@Slf4j
@Data
public class PublishResourceEventHandler<T> implements ResourceEventHandler<T> {
    private final ApplicationEventPublisher applicationEventPublisher;

    @Override
    public void onAdd(T obj) {
        log.trace("onAdd: {}", obj);
        applicationEventPublisher.publishEvent(
                new ResourceAdded<>(obj)
        );
    }

    @Override
    public void onUpdate(T oldObj, T newObj) {
        log.trace("onUpdate: {} -> {}", oldObj, newObj);
        applicationEventPublisher.publishEvent(new ResourceUpdated<>(oldObj, newObj));
    }

    @Override
    public void onDelete(T obj, boolean deletedFinalStateUnknown) {
        log.trace("onDelete: {}", obj);
        applicationEventPublisher.publishEvent(new ResourceRemoved<>(obj));
    }
}
