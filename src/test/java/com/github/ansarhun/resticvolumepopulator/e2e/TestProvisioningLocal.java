package com.github.ansarhun.resticvolumepopulator.e2e;

import com.github.ansarhun.resticvolumepopulator.config.KubernetesConfiguration;
import io.fabric8.kubernetes.client.Config;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

@SpringBootTest
public class TestProvisioningLocal extends TestProvisioning {

    @TestConfiguration
    public static class Configuration {
        @Bean
        public KubernetesConfiguration.KubernetesClientBuilderCustomizer testKubernetesClient() {
            return kubernetesClientBuilder ->
                    kubernetesClientBuilder.withConfig(
                            Config.fromKubeconfig(K3S_CONTAINER.getKubeConfigYaml())
                    );
        }
    }

}
