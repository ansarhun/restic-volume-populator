package com.github.ansarhun.resticvolumepopulator.k8s;

import io.fabric8.crd.generator.annotation.PrinterColumn;
import io.fabric8.generator.annotation.Default;
import lombok.Data;

@Data
public class ResticVolumePopulatorStatus {

    @PrinterColumn
    @Default("UNINITIALIZED")
    private Status status = Status.UNINITIALIZED;

    @PrinterColumn
    private String boundPVC;

    private String primePod;
    private String primePvc;

    public enum Status {
        UNINITIALIZED,
        BOUND,
        PROVISIONING,
        CLEANUP,
        FINISHED
    }
}
