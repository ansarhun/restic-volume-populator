package com.github.ansarhun.resticvolumepopulator.event;

import lombok.Data;
import org.springframework.core.ResolvableType;
import org.springframework.core.ResolvableTypeProvider;

@Data
public class ResourceUpdated<T> implements ResolvableTypeProvider {
    private final T oldResource;
    private final T newResource;

    @Override
    public ResolvableType getResolvableType() {
        return ResolvableType.forClassWithGenerics(
                getClass(),
                ResolvableType.forInstance(oldResource)
        );
    }
}
