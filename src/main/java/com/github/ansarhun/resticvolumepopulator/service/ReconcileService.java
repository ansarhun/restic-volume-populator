package com.github.ansarhun.resticvolumepopulator.service;

import com.github.ansarhun.resticvolumepopulator.event.ResourceAdded;
import com.github.ansarhun.resticvolumepopulator.event.ResourceRemoved;
import com.github.ansarhun.resticvolumepopulator.event.ResourceUpdated;
import com.github.ansarhun.resticvolumepopulator.k8s.ResticVolumePopulator;
import com.github.ansarhun.resticvolumepopulator.k8s.ResticVolumePopulatorStatus;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.events.v1.EventBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.PatchContext;
import io.fabric8.kubernetes.client.dsl.base.PatchType;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;

@RequiredArgsConstructor
@Slf4j
@Service
public class ReconcileService {

    private static final String POD_VOLUME_NAME = "source";
    private static final String POD_MOUNT_PATH = "/mnt";
    private static final String CONTAINER_NAME = "restic";

    private final static String RESTC_VOLUME_POPULATOR_API_GROUP = new ResticVolumePopulator().getGroup();
    private final static String RESTC_VOLUME_POPULATOR_KIND = new ResticVolumePopulator().getKind();

    private final static String OWNER_ANNOTATION_KEY = RESTC_VOLUME_POPULATOR_KIND.toLowerCase() + "." + RESTC_VOLUME_POPULATOR_API_GROUP + "/owner";

    private final ExecutorService reconcileTaskExecutor;

    private final KubernetesClient kubernetesClient;
    private final SharedIndexInformer<PersistentVolumeClaim> pvcInformer;

    @Value("${spring.application.name}")
    private String applicationName;

    private final CountDownLatch waitForStart = new CountDownLatch(1);

    @EventListener(ApplicationReadyEvent.class)
    @Order
    public void applicationStarted() {
        log.info("Starting worker thread");
        waitForStart.countDown();
    }

    /// region Event listeners

    @EventListener
    public void pvcAdded(ResourceAdded<PersistentVolumeClaim> event) {
        PersistentVolumeClaim persistentVolumeClaim = event.getResource();

        if (!isPvcWithResticVolumePopulator(persistentVolumeClaim)) {
            return;
        }

        ResourceId pvcKey = new ResourceId(persistentVolumeClaim);
        submit(() -> reconcilePVC(pvcKey));
    }

    @EventListener
    public void pvcUpdated(ResourceUpdated<PersistentVolumeClaim> event) {
        PersistentVolumeClaim pvc = event.getNewResource();

        String owner = pvc.getMetadata().getAnnotations().get(OWNER_ANNOTATION_KEY);
        if (StringUtils.hasText(owner)) {
            ResourceId ownerId = ResourceId.fromReference(owner);
            submit(() -> reconcilePVC(ownerId));
            return;
        }

        // For periodic reconcile
        if (isPvcWithResticVolumePopulator(pvc)) {
            ResourceId ownerId = new ResourceId(pvc);
            submit(() -> reconcilePVC(ownerId));
            return;
        }
    }

    @EventListener
    public void resticVolumePopulatorAdded(ResourceAdded<ResticVolumePopulator> event) {
        ResticVolumePopulator volumePopulator = event.getResource();

        ResourceId key = new ResourceId(volumePopulator);
        submit(() -> reconcileVolumePopulator(key));
    }

    @EventListener
    public void podAdded(ResourceAdded<Pod> event) {
        Pod pod = event.getResource();

        String owner = pod.getMetadata().getAnnotations().get(OWNER_ANNOTATION_KEY);
        if (!StringUtils.hasText(owner)) {
            return;
        }

        ResourceId ownerId = ResourceId.fromReference(owner);
        submit(() -> reconcilePVC(ownerId));
    }

    @EventListener
    public void pvcRemoved(ResourceRemoved<PersistentVolumeClaim> event) {
        PersistentVolumeClaim persistentVolumeClaim = event.getResource();

        if (!isPvcWithResticVolumePopulator(persistentVolumeClaim)) {
            return;
        }

        ResourceId volumePopulatorKey = getVolumePopulatorKey(persistentVolumeClaim);
        submit(() -> reconcileVolumePopulator(volumePopulatorKey));
    }

    @EventListener
    public void podUpdated(ResourceUpdated<Pod> event) {
        Pod pod = event.getNewResource();

        String owner = pod.getMetadata().getAnnotations().get(OWNER_ANNOTATION_KEY);
        if (!StringUtils.hasText(owner)) {
            return;
        }

        ResourceId ownerId = ResourceId.fromReference(owner);
        submit(() -> reconcilePVC(ownerId));
    }

    /// endregion

    // region Reconcile

    private void reconcilePVC(ResourceId pvcKey) {
        PersistentVolumeClaim pvc = kubernetesClient
                .persistentVolumeClaims()
                .inNamespace(pvcKey.namespace)
                .withName(pvcKey.name)
                .get();

        if (pvc == null) {
            return;
        }

        ResticVolumePopulator volumePopulator = kubernetesClient
                .resources(ResticVolumePopulator.class)
                .inNamespace(pvc.getMetadata().getNamespace())
                .withName(pvc.getSpec().getDataSourceRef().getName())
                .get();

        if (volumePopulator == null) {
            // wait for volume populator
            log.debug("Volume populator not found for pvc {}", pvcKey);
            return;
        }

        if (volumePopulator.getStatus() == null) {
            volumePopulator.setStatus(new ResticVolumePopulatorStatus());
        }

        ResticVolumePopulatorStatus.Status status = volumePopulator.getStatus().getStatus();
        if (status != ResticVolumePopulatorStatus.Status.FINISHED) {
            log.info("Reconciling pvc {} with status {}", pvcKey, status);
        } else {
            log.debug("Reconciling finished pvc {}", pvcKey);
        }

        switch (status) {
            case UNINITIALIZED -> actionInitialize(pvcKey, pvc, volumePopulator);
            case BOUND -> actionProvision(pvcKey, pvc, volumePopulator);
            case PROVISIONING -> actionRebind(pvcKey, pvc, volumePopulator);
            case CLEANUP -> actionCleanup(pvcKey, pvc, volumePopulator);
            case FINISHED -> {}
        }

        if (status != volumePopulator.getStatus().getStatus()) {
            sendEvent(
                    volumePopulator,
                    "StateChange",
                    "Reconcile status changed " + status + "->" + volumePopulator.getStatus().getStatus()
            );
            log.info("Reconcile status changed for pvc {} {}->{}", pvcKey, status, volumePopulator.getStatus().getStatus());
        }
    }

    private void reconcileVolumePopulator(ResourceId volumePopulatorKey) {
        ResticVolumePopulator volumePopulator = kubernetesClient
                .resources(ResticVolumePopulator.class)
                .inNamespace(volumePopulatorKey.namespace)
                .withName(volumePopulatorKey.name)
                .get();

        if (volumePopulator == null) {
            log.info("Volume populator not found {}", volumePopulatorKey);
            return;
        }

        if (
                volumePopulator.getStatus() != null &&
                        volumePopulator.getStatus().getStatus() != ResticVolumePopulatorStatus.Status.UNINITIALIZED
        ) {
            ResourceId pvcId = ResourceId.fromReference(volumePopulator.getStatus().getBoundPVC());
            PersistentVolumeClaim persistentVolumeClaim = kubernetesClient
                    .persistentVolumeClaims()
                    .inNamespace(pvcId.namespace)
                    .withName(pvcId.name)
                    .get();
            if (persistentVolumeClaim != null) {
                return;
            }

            ResticVolumePopulatorStatus.Status status = volumePopulator.getStatus().getStatus();
            log.info("PVC {} was removed for volume populator {} in {}", pvcId, volumePopulatorKey, status);
            log.debug("Previous status for volume populator {}: {}", volumePopulatorKey, volumePopulator.getStatus());

            volumePopulator.setStatus(new ResticVolumePopulatorStatus());
            kubernetesClient
                    .resource(volumePopulator)
                    .inNamespace(volumePopulator.getMetadata().getNamespace())
                    .updateStatus();

            sendEvent(
                    volumePopulator,
                    "Provision",
                    "Target PVC " + pvcId + " lost in status " + status
            );

            return;
        }

        Optional<PersistentVolumeClaim> foundPVC = pvcInformer
                .getStore()
                .list()
                .stream()
                .filter(this::isPvcWithResticVolumePopulator)
                .filter(pvc -> getVolumePopulatorKey(pvc).equals(volumePopulatorKey))
                .findAny();

        if (foundPVC.isEmpty()) {
            return;
        }

        ResourceId pvcKey = new ResourceId(foundPVC.get());
        submit(() -> reconcilePVC(pvcKey));
    }

    /// endregion

    /// region Reconcile actions

    private void actionInitialize(ResourceId pvcKey, PersistentVolumeClaim pvc, ResticVolumePopulator volumePopulator) {
        volumePopulator.getStatus().setBoundPVC(pvcKey.toReference());

        if ("Bound".equals(pvc.getStatus().getPhase())) {
            volumePopulator.getStatus().setStatus(ResticVolumePopulatorStatus.Status.FINISHED);

            sendEvent(
                    volumePopulator,
                    "Provision",
                    "PVC already provisioned " + pvcKey.name
            );
        } else {
            volumePopulator.getStatus().setStatus(ResticVolumePopulatorStatus.Status.BOUND);
        }

        kubernetesClient
                .resource(volumePopulator)
                .inNamespace(volumePopulator.getMetadata().getNamespace())
                .updateStatus();

        submit(() -> reconcilePVC(pvcKey));
    }

    private void actionProvision(ResourceId pvcKey, PersistentVolumeClaim pvc, ResticVolumePopulator volumePopulator) {
        String primeName = getPrimeName(pvc);

        PersistentVolumeClaim primePvc = createPrimePVC(pvc, volumePopulator, primeName);
        Pod primePod = createPrimePod(pvc, volumePopulator, primeName);

        kubernetesClient
                .persistentVolumeClaims()
                .inNamespace(primePvc.getMetadata().getNamespace())
                .resource(primePvc)
                .serverSideApply();

        sendEvent(
                volumePopulator,
                "Provision",
                "Prime PVC created " + primePvc.getMetadata().getName()
        );

        kubernetesClient
                .pods()
                .inNamespace(primePod.getMetadata().getNamespace())
                .resource(primePod)
                .serverSideApply();

        sendEvent(
                volumePopulator,
                "Provision",
                "Prime Pod created " + primePod.getMetadata().getName()
        );

        volumePopulator.getStatus().setStatus(ResticVolumePopulatorStatus.Status.PROVISIONING);
        volumePopulator.getStatus().setPrimePod(new ResourceId(primePod).toReference());
        volumePopulator.getStatus().setPrimePvc(new ResourceId(primePvc).toReference());

        kubernetesClient
                .resource(volumePopulator)
                .inNamespace(volumePopulator.getMetadata().getNamespace())
                .updateStatus();
    }

    private void actionRebind(ResourceId pvcKey, PersistentVolumeClaim pvc, ResticVolumePopulator volumePopulator) {
        ResourceId primePodId = ResourceId.fromReference(volumePopulator.getStatus().getPrimePod());
        Pod primePod = kubernetesClient
                .pods()
                .inNamespace(primePodId.namespace)
                .withName(primePodId.name)
                .get();

        ResourceId primePvcId = ResourceId.fromReference(volumePopulator.getStatus().getPrimePvc());
        PersistentVolumeClaim primePvc = kubernetesClient
                .persistentVolumeClaims()
                .inNamespace(primePvcId.namespace)
                .withName(primePvcId.name)
                .get();

        if (primePod == null || primePvc == null) {
            return;
        }

        if (
                primePod.getStatus().getContainerStatuses().isEmpty() ||
                primePod.getStatus().getContainerStatuses().getFirst().getState().getTerminated() == null
        ) {
            return;
        }

        String primePodLog = kubernetesClient
                .pods()
                .inNamespace(primePod.getMetadata().getNamespace())
                .resource(primePod)
                .getLog();

        ContainerStateTerminated terminated = primePod.getStatus().getContainerStatuses().getFirst().getState().getTerminated();

        sendEvent(
                volumePopulator,
                "Provision",
                "Prime Pod finished (" + terminated.getExitCode() + ")\n" + primePodLog
        );
        log.debug("Prime pod finished {}", primePodLog);

        switch (terminated.getReason()) {
            case "Error" -> {
                if (
                        !volumePopulator.getSpec().isAllowUninitializedRepository() ||
                        !primePodLog.contains("Is there a repository at the following location?")
                ) {
                    return;
                }

                log.debug("Uninitialized repository found");
            }
            case "Completed" -> {}

            case null, default -> {
                return;
            }
        }

        PersistentVolume persistentVolume = kubernetesClient
                .persistentVolumes()
                .withName(primePvc.getSpec().getVolumeName())
                .get();

        if (persistentVolume == null) {
            return;
        }

        if (
                !persistentVolume.getSpec().getClaimRef().getName().equals(pvc.getMetadata().getName()) ||
                        !persistentVolume.getSpec().getClaimRef().getNamespace().equals(pvc.getMetadata().getNamespace()) ||
                        !persistentVolume.getSpec().getClaimRef().getUid().equals(pvc.getMetadata().getUid())
        ) {
            PersistentVolume patch = new PersistentVolumeBuilder()
                    .withNewMetadata()
                        .withName(persistentVolume.getMetadata().getName())
                        .withAnnotations(Map.of())
                    .endMetadata()
                    .withNewSpec()
                    .withClaimRef(
                            new ObjectReferenceBuilder()
                                    .withName(pvc.getMetadata().getName())
                                    .withNamespace(pvc.getMetadata().getNamespace())
                                    .withUid(pvc.getMetadata().getUid())
                                    .withResourceVersion(pvc.getMetadata().getResourceVersion())
                                    .build()
                    )
                    .endSpec()
                    .build();

            kubernetesClient
                    .persistentVolumes()
                    .resource(persistentVolume)
                    .patch(PatchContext.of(PatchType.STRATEGIC_MERGE), patch);

            sendEvent(
                    volumePopulator,
                    "Provision",
                    "PV rebind complete"
            );
        }

        volumePopulator.getStatus().setStatus(ResticVolumePopulatorStatus.Status.CLEANUP);

        kubernetesClient
                .resource(volumePopulator)
                .inNamespace(volumePopulator.getMetadata().getNamespace())
                .updateStatus();
    }

    private void actionCleanup(ResourceId pvcKey, PersistentVolumeClaim pvc, ResticVolumePopulator volumePopulator) {
        ResourceId primePodId = ResourceId.fromReference(volumePopulator.getStatus().getPrimePod());
        Pod primePod = kubernetesClient
                .pods()
                .inNamespace(primePodId.namespace)
                .withName(primePodId.name)
                .get();

        ResourceId primePvcId = ResourceId.fromReference(volumePopulator.getStatus().getPrimePvc());
        PersistentVolumeClaim primePvc = kubernetesClient
                .persistentVolumeClaims()
                .inNamespace(primePvcId.namespace)
                .withName(primePvcId.name)
                .get();

        if (primePvc != null && !"Lost".equals(primePvc.getStatus().getPhase())) {
            return;
        }

        if (primePod != null) {
            kubernetesClient
                    .pods()
                    .inNamespace(primePod.getMetadata().getNamespace())
                    .resource(primePod)
                    .delete();

            sendEvent(
                    volumePopulator,
                    "Provision",
                    "Prime Pod removed"
            );
        }

        if (primePvc != null) {
            kubernetesClient
                    .persistentVolumeClaims()
                    .inNamespace(primePvc.getMetadata().getNamespace())
                    .resource(primePvc)
                    .delete();

            sendEvent(
                    volumePopulator,
                    "Provision",
                    "Prime PVC removed"
            );
        }

        volumePopulator.getStatus().setStatus(ResticVolumePopulatorStatus.Status.FINISHED);
        volumePopulator.getStatus().setPrimePod(null);
        volumePopulator.getStatus().setPrimePvc(null);

        kubernetesClient
                .resource(volumePopulator)
                .inNamespace(volumePopulator.getMetadata().getNamespace())
                .updateStatus();
    }

    /// endregion

    /// region Helpers

    private void submit(Runnable runnable) {
        reconcileTaskExecutor.execute(() -> reconcile(runnable));
    }

    private void reconcile(Runnable runnable) {
        try {
            waitForStart.await();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        runnable.run();
    }

    private void sendEvent(ResticVolumePopulator volumePopulator, String reason, String note) {
        String hostname = System.getenv("HOSTNAME");
        if (hostname == null) {
            hostname = applicationName;
        }

        try {
            kubernetesClient
                    .events()
                    .v1()
                    .events()
                    .inNamespace(volumePopulator.getMetadata().getNamespace())
                    .resource(
                            new EventBuilder()
                                    .withNewMetadata()
                                        .withNamespace(volumePopulator.getMetadata().getNamespace())
                                        .withName(volumePopulator.getMetadata().getName() + "." + UUID.randomUUID())
                                    .endMetadata()
                                    .withNewRegarding()
                                        .withKind(volumePopulator.getKind())
                                        .withNamespace(volumePopulator.getMetadata().getNamespace())
                                        .withName(volumePopulator.getMetadata().getName())
                                        .withUid(volumePopulator.getMetadata().getUid())
                                    .endRegarding()
                                    .withType("Normal")
                                    .withReason(reason)
                                    .withAction("provision")
                                    .withNote(note)
                                    .withEventTime(new MicroTime(Instant.now().toString()))
                                    .withReportingController(applicationName)
                                    .withReportingInstance(hostname)
                                    .build()
                    )
                    .create();
        } catch (Exception e) {
            log.warn("Failed to submit event {}", e.getMessage());
        }
    }

    private static String getPrimeName(PersistentVolumeClaim pvc) {
        return "prime-" + pvc.getMetadata().getName();
    }

    private static PersistentVolumeClaim createPrimePVC(PersistentVolumeClaim pvc, ResticVolumePopulator volumePopulator, String primeName) {
        PersistentVolumeClaim primePvc = new PersistentVolumeClaimBuilder()
                .withNewMetadata()
                .withName(primeName)
                .withNamespace(pvc.getMetadata().getNamespace())
                .withAnnotations(Map.of(
                        OWNER_ANNOTATION_KEY, new ResourceId(pvc).toReference()
                ))
                .endMetadata()
                .withNewSpec()
                .withAccessModes(pvc.getSpec().getAccessModes())
                .withResources(pvc.getSpec().getResources())
                .withStorageClassName(pvc.getSpec().getStorageClassName())
                .withVolumeMode(pvc.getSpec().getVolumeMode())
                .endSpec()
                .build();

        return primePvc;
    }


    private static Pod createPrimePod(PersistentVolumeClaim pvc, ResticVolumePopulator volumePopulator, String primeName) {
        Pod primePod = new PodBuilder()
                .withNewMetadata()
                .withName(primeName)
                .withNamespace(pvc.getMetadata().getNamespace())
                .withAnnotations(Map.of(
                        OWNER_ANNOTATION_KEY, new ResourceId(pvc).toReference()
                ))
                .endMetadata()
                .withNewSpec()
                .withContainers(
                        new ContainerBuilder()
                                .withName(CONTAINER_NAME)
                                .withImage(
                                        volumePopulator.getSpec().getImage().getRepository() + ":" + volumePopulator.getSpec().getImage().getTag()
                                )
                                .withArgs(
                                        "restore",
                                        volumePopulator.getSpec().getSnapshot(),
                                        "--target",
                                        "."
                                )
                                .withWorkingDir(POD_MOUNT_PATH)
                                .withEnvFrom(
                                        new EnvFromSourceBuilder()
                                                .withNewSecretRef()
                                                .withName(volumePopulator.getSpec().getSecretName())
                                                .endSecretRef()
                                                .build()
                                )
                                .withVolumeMounts(
                                        new VolumeMountBuilder()
                                                .withName(POD_VOLUME_NAME)
                                                .withMountPath(POD_MOUNT_PATH)
                                                .build()
                                )
                                .build()
                )
                .withVolumes(
                        new VolumeBuilder()
                                .withName(POD_VOLUME_NAME)
                                .withPersistentVolumeClaim(
                                        new PersistentVolumeClaimVolumeSourceBuilder()
                                                .withClaimName(primeName)
                                                .build()
                                )
                                .build()
                )
                .withRestartPolicy("Never")
                .withHostname(volumePopulator.getSpec().getHostname())
                .endSpec()
                .build();

        return primePod;
    }

    private boolean isPvcWithResticVolumePopulator(PersistentVolumeClaim pvc) {
        return pvc.getSpec().getDataSourceRef() != null &&
                RESTC_VOLUME_POPULATOR_API_GROUP.equals(pvc.getSpec().getDataSourceRef().getApiGroup()) &&
                RESTC_VOLUME_POPULATOR_KIND.equals(pvc.getSpec().getDataSourceRef().getKind());
    }

    private ResourceId getVolumePopulatorKey(PersistentVolumeClaim pvc) {
        String refNamespace = pvc.getSpec().getDataSourceRef().getNamespace();
        if (!StringUtils.hasText(refNamespace)) {
            refNamespace = pvc.getMetadata().getNamespace();
        }

        return new ResourceId(refNamespace, pvc.getSpec().getDataSourceRef().getName());
    }

    record ResourceId(String namespace, String name) {
        ResourceId(HasMetadata resource) {
            this(
                    resource.getMetadata().getNamespace(),
                    resource.getMetadata().getName()
            );
        }

        String toReference() {
            return namespace + "/" + name;
        }

        static ResourceId fromReference(String reference) {
            String[] split = reference.split("/");
            return new ResourceId(split[0], split[1]);
        }

        @Override
        public String toString() {
            return toReference();
        }
    }

    /// endregion

}
