package com.github.ansarhun.resticvolumepopulator.k8s;

import io.fabric8.generator.annotation.Required;
import lombok.Data;

@Data
public class ResticVolumePopulatorSpec {

    @Required
    private String secretName;

    @Required
    private String hostname;

    private String snapshot = "latest";

    private boolean allowUninitializedRepository = false;

    private Image image = new Image();

    @Data
    public static class Image {
        private String repository = "restic/restic";
        private String tag = "latest";
    }
}
