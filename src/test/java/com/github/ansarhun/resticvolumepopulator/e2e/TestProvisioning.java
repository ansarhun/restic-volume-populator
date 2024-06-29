package com.github.ansarhun.resticvolumepopulator.e2e;

import com.github.ansarhun.resticvolumepopulator.Versions;
import com.github.ansarhun.resticvolumepopulator.k8s.ResticVolumePopulator;
import com.github.ansarhun.resticvolumepopulator.k8s.ResticVolumePopulatorStatus;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.events.v1.Event;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.dsl.Gettable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.util.FileCopyUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
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
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

@Testcontainers(parallel = true)
public abstract class TestProvisioning {

    protected static final String MINIOADMIN = "minioadmin";
    protected static final String BUCKET = "test";
    protected static final String PATH = "lorem";
    protected static final String RESTIC_PASSWORD = "p4ssw0rd";

    protected static final String BUILD_TEST_KUBECONFIG_YAML = "./build/test-kubeconfig.yaml";

    protected static Network network = Network.newNetwork();

    @Container
    protected static final K3sContainer K3S_CONTAINER =
            new K3sContainer(DockerImageName.parse(Versions.K3S_VERSION))
                    .withCreateContainerCmdModifier(cmd -> cmd.withHostName("k3s"))
                    .withNetworkAliases("k3s")
                    .withNetwork(network);

    @Container
    protected static final GenericContainer<?> MINIO_CONTAINER =
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

    KubernetesClient kubernetesClient;

    Resource resource = new ClassPathResource("test-source/sample.txt");

    String namespace;

    @BeforeAll
    static void beforeAll() {
        executeRestic("--verbose init");
        executeRestic("--verbose backup .");
    }

    @BeforeAll
    static void writeKubeConfigFile() {
        try {
            File kubeConfigFile = new File(BUILD_TEST_KUBECONFIG_YAML);
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
    }

    @BeforeEach
    void setUp() {
        namespace = UUID.randomUUID().toString();

        kubernetesClient =
                new KubernetesClientBuilder()
                        .withConfig(Config.fromKubeconfig(K3S_CONTAINER.getKubeConfigYaml()))
                        .build();

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

    @Test
    void testProvisionedTargetRemoved() throws IOException {
        testProvision();

        kubernetesClient
                .persistentVolumeClaims()
                .inNamespace(namespace)
                .withName("test-pvc")
                .delete();

        kubernetesClient
                .resources(ResticVolumePopulator.class)
                .inNamespace(namespace)
                .withName("test-populator")
                .waitUntilCondition(
                        vp -> vp.getStatus() != null && ResticVolumePopulatorStatus.Status.UNINITIALIZED == vp.getStatus().getStatus(),
                        60,
                        TimeUnit.SECONDS
                );
    }

    @Test
    void testProvisionWithNonInitializedRepository() throws IOException {
        Secret secret = kubernetesClient
                .secrets()
                .inNamespace(namespace)
                .load(getClass().getResourceAsStream("/test-resources/secret.yaml"))
                .item();

        Map<String, String> resticEnvs = new HashMap<>(getResticEnvs());
        resticEnvs.put("RESTIC_REPOSITORY", resticEnvs.get("RESTIC_REPOSITORY") + "-ipsum");
        secret.setStringData(resticEnvs);

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

        ResticVolumePopulator resticVolumePopulator = kubernetesClient
                .resources(ResticVolumePopulator.class)
                .inNamespace(namespace)
                .load(getClass().getResourceAsStream("/test-resources/volume-populator.yaml"))
                .item();
        resticVolumePopulator.getSpec().setAllowUninitializedRepository(true);

        kubernetesClient
                .resources(ResticVolumePopulator.class)
                .inNamespace(namespace)
                .resource(resticVolumePopulator)
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
                listFromPvc(),
                equalTo(".\n..\n")
        );
    }

    String readDataFromPvc() {
        kubernetesClient
                .pods()
                .inNamespace(namespace)
                .load(getClass().getResourceAsStream("/test-resources/pod-cat.yaml"))
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

        String log = kubernetesClient
                .pods()
                .inNamespace(namespace)
                .withName("test-pvc-reader")
                .getLog();

        kubernetesClient
                .pods()
                .inNamespace(namespace)
                .withName("test-pvc-reader")
                .delete();

        return log;
    }

    String listFromPvc() {
        kubernetesClient
                .pods()
                .inNamespace(namespace)
                .load(getClass().getResourceAsStream("/test-resources/pod-ls.yaml"))
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

        String log = kubernetesClient
                .pods()
                .inNamespace(namespace)
                .withName("test-pvc-reader")
                .getLog();

        kubernetesClient
                .pods()
                .inNamespace(namespace)
                .withName("test-pvc-reader")
                .delete();

        return log;
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
                        ":9000/" + BUCKET + "/" + PATH
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
