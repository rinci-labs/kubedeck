# KubeDeck

KubeDeck is an Android Kubernetes client for inspecting and operating clusters from a phone,
built with Kotlin, Jetpack Compose and Material 3.

**[Visit the KubeDeck website](https://kubedeck.pages.dev/)** · **[Download the latest APK](https://github.com/rinci-labs/kubedeck/releases/tag/v0.1.0)**

One Gradle module, `:app`. Checked-in release configuration sets application id
`dev.rafa.kubemobile`, `minSdk 26`, `targetSdk`/`compileSdk 37`, and `versionName 0.1.0`.
The APK at `app/build/outputs/apk/release/app-release.apk` reports `versionName 0.1.0` and
`versionCode 17` in the readable binary-manifest strings. The checked-in build file says
`versionCode 1`; that mismatch is reported here. The source resource label is KubeDeck; binary
manifest text exposed the label attribute but not its resolved string value.

## Features

### Clusters

- **Multi-cluster management** â€” every stored cluster is one `ClusterProfile`; the active one is
  selected on the Clusters screen and remembered across launches.
- **Kubeconfig import** â€” paste the YAML or pick a file with the system document picker
  (`ActivityResultContracts.OpenDocument`). Every `contexts[]` entry becomes its own profile, so
  one file holding staging + prod yields two switchable clusters, matching
  `kubectl config get-contexts`. Contexts are listed for review and can be deselected
  individually or all at once before import.
- **Manual cluster entry** â€” name, server URL, namespace, and auth by bearer token, username +
  password, or client certificate + key, with an optional custom CA.
- **Encrypted credential storage** â€” profiles are serialised to JSON, encrypted with AES-256-GCM
  under a hardware-backed AndroidKeyStore key, and written to DataStore (see
  [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md#credential-storage)).
- **Transport options** â€” custom CA / client certificates, `insecure-skip-tls-verify`,
  `tls-server-name`, and `proxy-url` are honoured from the kubeconfig; TLS verification can also
  be toggled per cluster from Settings.
- **Cluster summary** â€” tapping a connected cluster opens `cluster/{profileId}`: a header with the
  connection chip and `GET /version` Kubernetes version, four stat tiles (nodes ready, namespaces,
  pods running, deployments available), cluster CPU/memory used against the sum of the nodes'
  `status.allocatable`, a *Needs attention* card (pods not ready, deployments unavailable, failing
  Flux Kustomizations/HelmReleases, the five most recent `Warning` events) and quick links to
  Browse, Events and the three GitOps tabs. Every number is a real list call and every source is
  independent, so a 403 on one kind leaves its tile as `â€”` rather than zero. It is honest about a
  cluster with no metrics-server (an explicit "metrics unavailable" state and a hint, never zeroes).
  Tiles and usage are pull-to-refreshable and the whole card set re-derives from a live reload.

### Browsing

- **Dynamic API discovery** â€” the browse catalog is built from `/api`, `/apis` and every served
  group version. Any CRD (Flux, Argo CD, cert-manager, Istio, any operator) is browsable with no
  per-operator code. Duplicate versions of a resource collapse to one addressable version.
- **Browse catalog** â€” a namespace selector, a search field (kind, plural, group, short names,
  categories), a scope filter (*All* / namespaced / cluster-scoped), a multi-select API-group
  filter, and dashboard tiles shown only when nothing is filtered. Below them: *Recent* (the last
  six kinds opened on this cluster, newest first), *Common* (a curated list of the plurals an
  engineer reaches for first), collapsible *API groups* (preferred version per group, with a kind
  count) and *Core v1*. A grouped kind whose group document was missing still appears under
  *Custom resources*, so nothing is unreachable. Filters and expanded groups survive rotation.
- **Resource lists** â€” name filter, label selector, a 500-object page with truncation flagged,
  pull-to-refresh, and namespace scoping. Namespaced resources start in the profile's namespace and
  can be switched to any single namespace or to *All namespaces*; cluster-scoped resources ignore
  the choice. If a cluster-wide list is refused by RBAC, the list is assembled namespace by
  namespace and the partial result is flagged. Pods rows carry CPU/memory usage when metrics-server
  is present.
- **Resource detail** â€” a tab row of `Summary | YAML | Events | Controller` for every kind, with
  kind-specific tabs inserted: `Pods` for workloads (Deployment, ReplicaSet, ReplicationController,
  StatefulSet, DaemonSet, Job, CronJob) and Nodes, `Containers` for a Pod, `Jobs` for a CronJob,
  and `ReplicaSets` for a Deployment. Summary has the health chip, kind-specific status rows,
  conditions, labels and annotations; Controller has the controlling spec and clickable owner
  references; ReplicaSets is a Deployment's revision history with the live revision flagged.
- **Health chips and conditions** â€” interpreted per kind for built-in workloads and generically
  from the `status.conditions[]` convention for CRDs; see
  [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md#status-interpretation).
- **Create / YAML apply** â€” the create action seeds a valid starting manifest per kind; the YAML
  tab is read-only until *Edit*, then *Apply* round-trips through the API.
- **Scale** â€” `spec.replicas` via the `scale` subresource, so HPA-managed workloads stay sane.
- **Rollout restart** â€” stamps `kubectl.kubernetes.io/restartedAt` on the pod template.
- **Rollout undo** â€” picks a target ReplicaSet from the deployment's ReplicaSet history
  (newest-first list, or "the revision before the current one") and re-points the pod template.
- **Delete** â€” per object, with a confirmation dialog.

### Inspect and connect

- **Events** â€” cluster-wide feed, newest first, grouped by involved object, with a
  Normal/Warning chip pair, a grouped-vs-by-time toggle, namespace scope from the feed itself,
  and copy (a count of visible events in the subtitle).
- **Logs** â€” follow, tail size (100 / 500 / 1000 / 5000), timestamps, previous container, and
  container selection; auto-scroll pauses when you scroll back, copy-whole-log and share. A
  workload's logs (Deployment, StatefulSet, DaemonSet, ReplicaSet, ReplicationController, Job,
  CronJob) are merged from the pods it owns, resolved from the object's own selector; a per-pod
  chip row switches between *All pods* and a single pod, and merged lines are prefixed with
  `pod |`. At most 20 pods are followed at once and the screen says how many were skipped.
- **Metrics in the UI** â€” with metrics-server installed, pod CPU/memory appear on the pods list
  and a Pod's Summary tab. The cluster summary shows cluster-wide pod usage against summed node
  allocatable CPU/memory. A Node's Summary shows its `status.allocatable` and `status.capacity`
  (not live per-node usage). The UI distinguishes an absent metrics API from a failed or refused
  metrics request rather than substituting zeroes.
- **Node operations** â€” Cordon/Uncordon (a merge patch of `spec.unschedulable`) and Drain, both on
  the node detail screen. Drain uses the `policy/v1` Eviction subresource, so PodDisruptionBudgets
  are honoured and a refused eviction surfaces the API server's message. DaemonSet-owned pods are
  skipped unless the dialog option includes them; Node-owned mirror pods are skipped. Drain does
  not cordon the node by itself.
- **Interactive terminal** â€” `pods/exec` over the `v4.channel.k8s.io` WebSocket protocol, with
  container selection, shell selection (`/bin/sh`, `/bin/bash`, `/bin/ash`), optional TTY, control
  keys (Ctrl-C, Esc, Tab), and terminal resize forwarding.
- **Port forwarding** â€” `pods/portforward` over `SPDY/3.1+portforward.k8s.io` (SPDY/3.1 frames
  inside a WebSocket). Binds a loopback port per forward, lists the pod's declared container ports
  with a *Forward* button each, accepts a custom remote port, shows up/down byte counters, and
  stops forwards individually or all at once.
- **Helm releases** â€” reads Helm 3 release secrets (`owner=helm`) and Helm 2 Tiller config maps
  (`OWNER=TILLER`), decoding `base64(gzip(json))`. Shows overview, chart/user values with a
  defaults-vs-overrides diff, notes, manifest with a resource list and search, and full revision
  history. Uninstall can optionally delete the resources in the newest revision's manifest first.
- **Flux dashboard** â€” Kustomizations, HelmReleases and sources (GitRepository, OCIRepository,
  HelmRepository, Bucket), with ready-count summary tiles, reconcile (optionally with the source),
  and suspend/resume.
- **Argo CD dashboard** â€” Applications and ApplicationSets, with refresh, hard refresh, sync
  (optional revision, prune, dry run) and terminate operation.
- **Helm/Flux/Argo from the object detail screen** â€” the matching actions appear when the object's
  kind and group match.

### Presentation

- **Multi-namespace and cluster-scoped browsing** throughout the resource, Helm, Flux, Argo and
  events surfaces.
- **Dark / light with Material You** â€” dynamic colour on Android 12+ (`dynamicLightColorScheme` /
  `dynamicDarkColorScheme`), otherwise a built-in blue light/dark scheme.
- **Shared layout rhythm and tabs** â€” screens use the central `Spacing` scale in `UiSupport.kt`;
  Object detail, Helm release detail and GitOps use the shared `components/TabStrip`, which chooses
  evenly distributed or horizontally scrollable tabs based on available width and measured labels.
- **Edge-to-edge layout**, per-destination top app bar, bottom navigation bar, snackbar messages.

## What it deliberately does not do

- **`exec` credential plugins.** The parser cannot and does not run a binary, so an
  `exec` block in a kubeconfig is reported as a warning ("cannot run on Android") rather than
  silently ignored. The command and args are still stored on the profile so the import is
  lossless, and Settings repeats the warning. There is no way to make such a context work â€” supply
  a static token or certificate instead.
- **Interactive OIDC / `auth-provider` flows.** The parser extracts a token that is already present
  in the config (`access-token`, `id-token`, `token`) and warns when only an auth-provider is
  present. It does not perform a device-code or browser login, and does not refresh an expired
  token.
- **Encrypted private keys.** `Pem.privateKey` rejects `BEGIN ENCRYPTED PRIVATE KEY` with an
  explicit error; decrypt the key before importing.
- **`--watch` live lists.** A streaming watch client exists in `k8s/Transport.kt`
  (`Watches.stream`), but no screen subscribes to it; lists refresh on demand or by pull-to-refresh.
- **Networking outside the API server.** There is no SSH, no direct pod access, no `kubectl`
  passthrough, and no Helm/Flux/Argo binaries: every action is a plain API-server call, so it works
  only as far as your RBAC allows.
- **Cleartext HTTP.** `android:usesCleartextTraffic="false"` and there is no network security config
  exempting hosts, so an `http://` API server will be blocked by the platform.

## Status and limitations

The previous release APK was device-tested before some of the latest UI additions. The final
KubeDeck-branded release is being installed on a Xiaomi device now; no result from that installation
is available for this documentation pass, so final-device verification remains pending.

**Previously reported as verified in development**

- The debug build was exercised on an Android device, and a JVM harness is reported to have proven
  14 capabilities against a live cluster (cluster connect and discovery, resource list/get,
  create/apply/replace/delete, scale, rollout restart/undo, logs, exec, port-forward, Helm release
  decoding, Flux and Argo CD operations).

**Not verifiable from this repository**

- **The harness is not committed.** There is no test source set â€” `app/src` contains only
  `app/src/main`, with no `test/` or `androidTest/`, no CI configuration, and no fixture. Nothing in
  this tree exercises the code automatically, so none of the reported results above can be
  reproduced from the source as committed. Every factual claim in these docs is read from the
  source, not executed.
- The **build** itself is reproducible: the Gradle wrapper is committed (`gradlew` / `gradlew.bat`,
  pinned to Gradle 9.7.1 with a verified distribution checksum). See
  [docs/BUILD.md](docs/BUILD.md).

**Verification status**

- The earlier release APK device run predates some of the latest cluster-summary/attention
  handling, compact Browse filters, shared tab/spacing consolidation and final brand identity.
  Those additions are not claimed covered by that run. Final-device verification is pending the
  current Xiaomi installation report.
- The release is minified (`isMinifyEnabled = true`) and resource-shrunk
  (`isShrinkResources = true`); shrinking and obfuscation can change behaviour.
- No claim about behaviour against a live cluster is checked by anything in this repository.

## Building

See [docs/BUILD.md](docs/BUILD.md) for exact toolchain versions and commands.

## Documentation

- [docs/BUILD.md](docs/BUILD.md) â€” toolchain, build and install commands, AGP 9 caveats.
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) â€” package layout, request path, discovery,
  credential storage, transports, status model.
- [docs/FEATURES.md](docs/FEATURES.md) â€” screen-by-screen reference with routes and arguments.
