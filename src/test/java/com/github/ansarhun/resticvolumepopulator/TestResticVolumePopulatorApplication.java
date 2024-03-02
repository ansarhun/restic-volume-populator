package com.github.ansarhun.resticvolumepopulator;

import com.github.ansarhun.resticvolumepopulator.config.KubernetesConfiguration;
import io.fabric8.kubernetes.client.Config;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.devtools.restart.RestartScope;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.testcontainers.k3s.K3sContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class TestResticVolumePopulatorApplication {

	@Bean
	@RestartScope
	public K3sContainer k3sContainer() {
		return new K3sContainer(
				DockerImageName.parse("rancher/k3s:v1.29.2-k3s1")
		);
	}

	@Bean
	public KubernetesConfiguration.KubernetesClientBuilderCustomizer testKubernetesClient(K3sContainer k3sContainer) {
		return kubernetesClientBuilder ->
				kubernetesClientBuilder.withConfig(
					Config.fromKubeconfig(k3sContainer.getKubeConfigYaml())
				);
	}

	public static void main(String[] args) {
		SpringApplication
				.from(ResticVolumePopulatorApplication::main)
				.with(TestResticVolumePopulatorApplication.class)
				.run(args);
	}

}
