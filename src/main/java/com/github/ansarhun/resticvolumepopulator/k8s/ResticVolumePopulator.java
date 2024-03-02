package com.github.ansarhun.resticvolumepopulator.k8s;

import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.Version;
import lombok.Data;

@Group("ansarhun.github.com")
@Version("v1alpha1")
@Data
public class ResticVolumePopulator
        extends CustomResource<ResticVolumePopulatorSpec, ResticVolumePopulatorStatus>
        implements Namespaced {

}
