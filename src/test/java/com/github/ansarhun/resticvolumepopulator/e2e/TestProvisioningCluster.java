package com.github.ansarhun.resticvolumepopulator.e2e;

import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.utility.MountableFile;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

public class TestProvisioningCluster extends TestProvisioning {

    @BeforeAll
    static void initializeCluster() throws IOException, InterruptedException {
        Process process = Runtime.getRuntime().exec(new String[]{
                "docker", "save", "restic-volume-populator:0.0.1-SNAPSHOT"
        });

        File dockerImageFile = new File("build/docker.image");
        dockerImageFile.deleteOnExit();

        try (InputStream inputStream = process.getInputStream()) {
            Files.copy(
                    inputStream,
                    dockerImageFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
            );
        }

        K3S_CONTAINER.copyFileToContainer(
                MountableFile.forHostPath("./build/docker.image"),
                "/tmp/docker.image"
        );

        K3S_CONTAINER.execInContainer("ctr", "image", "import", "/tmp/docker.image");

        Process helmProcess = Runtime.getRuntime().exec(new String[]{
                "helm",
                "--kubeconfig", BUILD_TEST_KUBECONFIG_YAML,
                "install",
                "--wait",
                "restic-volume-populator",
                "./charts/restic-volume-populator",
                "--set", "image.repository=restic-volume-populator",
                "--set", "image.tag=0.0.1-SNAPSHOT"
        });
        helmProcess.getInputStream().transferTo(System.out);
        helmProcess.waitFor();
    }

}
