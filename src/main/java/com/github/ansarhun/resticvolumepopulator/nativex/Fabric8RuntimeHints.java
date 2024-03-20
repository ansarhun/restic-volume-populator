package com.github.ansarhun.resticvolumepopulator.nativex;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.github.ansarhun.resticvolumepopulator.k8s.ResticVolumePopulator;
import com.github.ansarhun.resticvolumepopulator.k8s.ResticVolumePopulatorSpec;
import com.github.ansarhun.resticvolumepopulator.k8s.ResticVolumePopulatorStatus;
import io.fabric8.kubernetes.api.model.AnyType;
import io.fabric8.kubernetes.api.model.KubernetesResource;
import io.fabric8.kubernetes.api.model.KubernetesResourceList;
import io.fabric8.kubernetes.client.Client;
import io.fabric8.kubernetes.client.VersionInfo;
import lombok.SneakyThrows;
import org.reflections.Reflections;
import org.reflections.util.ConfigurationBuilder;
import org.springframework.aot.hint.*;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportRuntimeHints;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;
import java.util.stream.Collectors;

@Configuration
@ImportRuntimeHints(Fabric8RuntimeHints.Fabric8Registar.class)
@RegisterReflectionForBinding({ ResticVolumePopulator.class, ResticVolumePopulatorSpec.class, ResticVolumePopulatorStatus.class})
public class Fabric8RuntimeHints {

    // Based on https://github.com/fabric8io/kubernetes-client/issues/5084#issuecomment-1591139313
    static class Fabric8Registar implements RuntimeHintsRegistrar {
        @Override
        public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
            registerResources(hints);
            registerClients(hints, classLoader);
            registerJacksonKubernetesModels(hints, classLoader);
        }

        private void registerResources(RuntimeHints hints) {
            Set.of(
                    "META-INF/services/io.fabric8.kubernetes.api.model.KubernetesResource",
                    "META-INF/services/io.fabric8.kubernetes.client.http.HttpClient$Factory"
            ).forEach(hints.resources()::registerPattern);
        }

        private void registerClients(RuntimeHints hints, ClassLoader classLoader) {
            hints
                    .reflection()
                    .registerType(Client.class, MemberCategory.values());
            hints
                    .reflection()
                    .registerType(VersionInfo.class, MemberCategory.values());

            Class<Client> clazz = Client.class;
            Reflections reflections = new Reflections(
                    new ConfigurationBuilder()
                            .forPackage(clazz.getPackageName(), classLoader)
            );

            reflections
                    .getSubTypesOf(clazz)
                    .forEach(c -> hints.reflection().registerType(c, MemberCategory.values()));
        }

        private void registerJacksonKubernetesModels(RuntimeHints hints, ClassLoader classLoader) {
            Class<KubernetesResource> clazz = KubernetesResource.class;

            Reflections reflections = new Reflections(
                    new ConfigurationBuilder()
                            .forPackage(clazz.getPackageName(), classLoader)
            );

            HashSet<Class<?>> classes = new HashSet<>();

            classes.addAll(reflections.getSubTypesOf(KubernetesResource.class));
            classes.addAll(reflections.getSubTypesOf(KubernetesResourceList.class));
            classes.addAll(reflections.getSubTypesOf(AnyType.class));

            classes.addAll(resolveSerializationClasses(JsonSerialize.class, reflections));
            classes.addAll(resolveSerializationClasses(JsonDeserialize.class, reflections));

            classes
                    .forEach(c -> hints.reflection().registerType(c, MemberCategory.values()));
        }

        @SneakyThrows
        private <R extends Annotation> Set<Class<?>> resolveSerializationClasses(Class<R> annotationClazz, Reflections reflections) {
            Method method = annotationClazz.getMethod("using");
            Set<Class<?>> classes = reflections.getTypesAnnotatedWith(annotationClazz);
            return classes
                    .stream()
                    .map(c -> {
                        var annotation = c.getAnnotation(annotationClazz);
                        if (annotation == null) {
                            return null;
                        }

                        try {
                            return (Class<?>) method.invoke(annotation);
                        } catch (IllegalAccessException | InvocationTargetException e) {
                            return null;
                        }
                    })
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
        }
    }
}
