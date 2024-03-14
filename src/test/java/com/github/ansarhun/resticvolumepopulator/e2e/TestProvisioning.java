package com.github.ansarhun.resticvolumepopulator.e2e;

import com.github.ansarhun.resticvolumepopulator.Versions;
import com.github.ansarhun.resticvolumepopulator.config.KubernetesConfiguration;
import com.github.ansarhun.resticvolumepopulator.k8s.ResticVolumePopulator;
import com.github.ansarhun.resticvolumepopulator.k8s.ResticVolumePopulatorStatus;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.events.v1.Event;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.Gettable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.Resource;
import org.springframework.util.FileCopyUtils;
import org.testcontainers.containers.*;
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.k3s.K3sContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

@Testcontainers(parallel = true)
@SpringBootTest
public class TestProvisioning {

    private static final String MINIOADMIN = "minioadmin";
    private static final String BUCKET = "test";
    private static final String RESTIC_PASSWORD = "p4ssw0rd";

    private static Network network = Network.newNetwork();

    @Container
    private static final K3sContainer K3S_CONTAINER =
            new K3sContainer(DockerImageName.parse(Versions.K3S_VERSION))
                    .withNetwork(network);

    @Container
    private static final GenericContainer<?> MINIO_CONTAINER =
            new GenericContainer<>(DockerImageName.parse(Versions.MINIO_VERSION))
                    .withExposedPorts(9000, 9001)
                    .waitingFor(Wait.forHttp("/minio/health/live").forPort(9000).withStartupTimeout(Duration.of(60L, ChronoUnit.SECONDS)))
                    .withNetwork(network)
                    .withNetworkAliases("minio")
                    .withEnv(Map.of(
                            "MINIO_DEFAULT_BUCKETS", BUCKET,
                            "MINIO_ROOT_USER", MINIOADMIN,
                            "MINIO_ROOT_PASSWORD", MINIOADMIN
                    ));

    @TestConfiguration
    public static class Configuration {
        @Bean
        public KubernetesConfiguration.KubernetesClientBuilderCustomizer testKubernetesClient() {
            return kubernetesClientBuilder ->
                    kubernetesClientBuilder.withConfig(
                            Config.fromKubeconfig(K3S_CONTAINER.getKubeConfigYaml())
                    );
        }

        @Bean
        public ApplicationListener<ApplicationReadyEvent> writeKubeConfigFile() {
            return event -> {
                try {
                    File kubeConfigFile = new File("build/test-kubeconfig.yaml");
                    kubeConfigFile.deleteOnExit();

                    if (!kubeConfigFile.exists()) {
                        try (FileWriter writer = new FileWriter(kubeConfigFile)) {
                            writer.write(K3S_CONTAINER.getKubeConfigYaml());
                        }
                    }

                    System.out.println("=========================");
                    System.out.println("=========================");
                    System.out.printf("|| Kubernetes Config file generated at: %s\n", kubeConfigFile.getAbsolutePath());
                    System.out.printf("|| export KUBECONFIG=%s\n", kubeConfigFile.getAbsolutePath());
                    System.out.println("=========================");
                    System.out.println("=========================");
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            };
        }
    }

    @Autowired
    KubernetesClient kubernetesClient;

    @Value("classpath:test-source/sample.txt")
    Resource resource;

    String namespace;

    @BeforeAll
    static void beforeAll() {
        executeRestic("--verbose init");
        executeRestic("--verbose backup .");
    }

    @BeforeEach
    void setUp() {
        namespace = UUID.randomUUID().toString();

        kubernetesClient
                .namespaces()
                .resource(
                    new NamespaceBuilder()
                            .withNewMetadata()
                                .withName(namespace)
                            .endMetadata()
                            .build()
                )
                .create();
    }

    @AfterEach
    void tearDown() {
        String events = kubernetesClient
                .events()
                .v1()
                .events()
                .inNamespace(namespace)
                .withField("regarding.name", "test-populator")
                .resources()
                .map(Gettable::get)
                .map(Event::getNote)
                .collect(Collectors.joining("\n"));

        System.out.println("--- EVENTS");
        System.out.println(events);
        System.out.println("+++");

        kubernetesClient
                .namespaces()
                .withName(namespace)
                .delete();
    }

    @Test
    void testProvision() throws IOException {
        Secret secret = kubernetesClient
                .secrets()
                .inNamespace(namespace)
                .load(getClass().getResourceAsStream("/test-resources/secret.yaml"))
                .item();

        secret.setStringData(getResticEnvs());

        kubernetesClient
                .secrets()
                .inNamespace(namespace)
                .resource(secret)
                .create();

        kubernetesClient
                .persistentVolumeClaims()
                .inNamespace(namespace)
                .load(getClass().getResourceAsStream("/test-resources/pvc.yaml"))
                .create();

        kubernetesClient
                .resources(ResticVolumePopulator.class)
                .inNamespace(namespace)
                .load(getClass().getResourceAsStream("/test-resources/volume-populator.yaml"))
                .create();

        kubernetesClient
                .resources(ResticVolumePopulator.class)
                .inNamespace(namespace)
                .withName("test-populator")
                .waitUntilCondition(
                        vp -> vp.getStatus() != null && ResticVolumePopulatorStatus.Status.FINISHED == vp.getStatus().getStatus(),
                        60,
                        TimeUnit.SECONDS
                );

        kubernetesClient
                .persistentVolumeClaims()
                .inNamespace(namespace)
                .withName("test-pvc")
                .waitUntilCondition(
                        pvc -> pvc.getStatus() != null && "Bound".equals(pvc.getStatus().getPhase()),
                        5,
                        TimeUnit.SECONDS
                );

        assertThat(
                readDataFromPvc(),
                equalTo(FileCopyUtils.copyToString(new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8)))
        );
    }

    String readDataFromPvc() {
        kubernetesClient
                .pods()
                .inNamespace(namespace)
                .load(getClass().getResourceAsStream("/test-resources/pod.yaml"))
                .create();

        kubernetesClient
                .pods()
                .inNamespace(namespace)
                .withName("test-pvc-reader")
                .waitUntilCondition(
                        pod -> "Succeeded".equals(pod.getStatus().getPhase()),
                        60,
                        TimeUnit.SECONDS
                );

        return kubernetesClient
                .pods()
                .inNamespace(namespace)
                .withName("test-pvc-reader")
                .getLog();
    }

    static Map<String, String> getResticEnvs() {
        return Map.of(
                "AWS_ACCESS_KEY_ID", MINIOADMIN,
                "AWS_SECRET_ACCESS_KEY", MINIOADMIN,
                "RESTIC_PASSWORD", RESTIC_PASSWORD,

                "RESTIC_REPOSITORY",
                "s3:http://" +
                        MINIO_CONTAINER
                                .getContainerInfo()
                                .getNetworkSettings()
                                .getNetworks()
                                .values()
                                .stream().findAny()
                                .orElseThrow()
                                .getIpAddress() +
                        ":9000/" + BUCKET
        );
    }

    static void executeRestic(String command) {
        try (GenericContainer<?> resticContainer = new GenericContainer<>(DockerImageName.parse(Versions.RESTIC_VERSION))) {
            resticContainer.withNetwork(network);
            resticContainer.withCreateContainerCmdModifier(
                    cmd -> cmd.withHostName("e2e-test").withWorkingDir("/mnt")
            );

            resticContainer.withEnv(getResticEnvs());

            resticContainer.withCopyFileToContainer(
                    MountableFile.forClasspathResource("/test-source/"),
                    "/mnt"
            );

            resticContainer.withCommand(command);

            resticContainer.withStartupCheckStrategy(
                    new OneShotStartupCheckStrategy().withTimeout(Duration.ofSeconds(5))
            );

            resticContainer.start();

            System.out.println("-------------------------------------");
            System.out.println("Executed restic " + command);
            System.out.println(resticContainer.getLogs());
            System.out.println("-------------------------------------");
        }
    }
}
