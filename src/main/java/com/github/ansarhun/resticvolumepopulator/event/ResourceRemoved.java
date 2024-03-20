package com.github.ansarhun.resticvolumepopulator.event;

import lombok.Data;
import org.springframework.core.ResolvableType;
import org.springframework.core.ResolvableTypeProvider;

@Data
public class ResourceRemoved<T> implements ResolvableTypeProvider {
    private final T resource;

    @Override
    public ResolvableType getResolvableType() {
        return ResolvableType.forClassWithGenerics(
                getClass(),
                ResolvableType.forInstance(resource)
        );
    }
}
