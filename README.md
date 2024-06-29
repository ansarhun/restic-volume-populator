# Restic volume populator

Restic volume populator is a Kubernetes [Volume populator](https://kubernetes.io/docs/concepts/storage/persistent-volumes/#volume-populators-and-data-sources) to provision a PVC from a restic snapshot.

[![Docker](https://github.com/ansarhun/restic-volume-populator/actions/workflows/docker-publish.yaml/badge.svg)](https://github.com/ansarhun/restic-volume-populator/actions/workflows/docker-publish.yaml) [![Helm](https://github.com/ansarhun/restic-volume-populator/actions/workflows/helm-publish.yaml/badge.svg)](https://github.com/ansarhun/restic-volume-populator/actions/workflows/helm-publish.yaml) [![Java CI with Gradle](https://github.com/ansarhun/restic-volume-populator/actions/workflows/ci.yml/badge.svg)](https://github.com/ansarhun/restic-volume-populator/actions/workflows/ci.yml)

## Install the operator

The following command will install the operator using helm:
```shell
helm install restic-volume-populator oci://ghcr.io/ansarhun/charts/restic-volume-populator --version <VERSION>
```

## Basic example

Create a secret with the following content:
```yaml
apiVersion: v1
kind: Secret
metadata:
  name: freshrss-restic
type: Opaque
stringData:
  AWS_ACCESS_KEY_ID: "<AWS_ACCESS_KEY_ID>"
  AWS_SECRET_ACCESS_KEY: "<AWS_SECRET_ACCESS_KEY>"
  RESTIC_PASSWORD: "<RESTIC_PASSWORD>"
  RESTIC_REPOSITORY: "<RESTIC_REPOSITORY>"
```

Create the **ResticVolumePopulator** resource with the hostname used in the restic snapshot and the name of the secret created above:
```yaml
apiVersion: ansarhun.github.com/v1alpha1
kind: ResticVolumePopulator
metadata:
  name: freshrss
spec:
  secretName: freshrss-restic
  hostname: volsync
```

Initialize the **PersistentVolumeClaim** using the `dataSourceRef` field and refer to the **ResticVolumePopulator** created above:
```yaml
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: freshrss
spec:
  accessModes:
    - ReadWriteOnce
  volumeMode: Filesystem
  resources:
    requests:
      storage: 100Mi
  dataSourceRef:
    apiGroup: ansarhun.github.com
    kind: ResticVolumePopulator
    name: freshrss
```

> [!NOTE]
> All of these resources must be in the same namespace.

With these resources, the operator will provision the **PersistentVolumeClaim** with the content from the restic snapshot.

## ResticVolumePopulator resource

| Field                        | Required           | Default value | Description                                                                                                      |
|------------------------------|--------------------|---------------|------------------------------------------------------------------------------------------------------------------|
| secretName                   | :white_check_mark: |               | The name of the secret containing parameters for the restic command, e.g.: `RESTIC_REPOSITORY`                   |
| hostname                     | :white_check_mark: |               | The name of the host from the restic snapshot, e.g.: `volsync` for [VolSync](https://github.com/backube/volsync) |
| snapshot                     |                    | latest        | The id/name of the snapshot                                                                                      |
| allowUninitializedRepository |                    | false         | Allow provision from an uninitialized repository (will result in an empty PVC)                                   |
| image.repository             |                    | restic/restic | Image to use for restic restore                                                                                  |
| image.tag                    |                    | latest        | Image tag to use for restic restore                                                                              |

## Project status

The project is in the early stages of development.

It was tested to be working with: restic snapshots created with [VolSync](https://github.com/backube/volsync),
[Longhorn](https://github.com/longhorn/longhorn) for stage and [Minio](https://min.io) as the restic repository.

## References

The project was inspired by the https://github.com/kubernetes-csi/lib-volume-populator/ project.
