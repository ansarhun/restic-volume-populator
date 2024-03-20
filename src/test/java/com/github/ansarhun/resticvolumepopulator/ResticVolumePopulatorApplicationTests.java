package com.github.ansarhun.resticvolumepopulator;

import com.github.ansarhun.resticvolumepopulator.config.KubernetesConfiguration;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

@SpringBootTest
@EnableKubernetesMockClient(crud = true)
class ResticVolumePopulatorApplicationTests {

	static KubernetesClient client;

	@TestConfiguration
	static class TestContextConfiguration {

		@Bean
		public KubernetesConfiguration.KubernetesClientBuilderCustomizer testKubernetesClient() {
			return kubernetesClientBuilder ->
					kubernetesClientBuilder.withConfig(client.getConfiguration());
		}
	}

	@Test
	void contextLoads() {
	}

}
