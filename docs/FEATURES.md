# Screen reference

Twelve navigation destinations. Routes and arguments are quoted from `ui/Navigation.kt`
(`object Routes` and the `composable(...)` registrations); every screen composable lives under
`app/src/main/java/dev/rafa/kubemobile/ui/`.

## Destinations and routes

| # | Screen | Composable | Route pattern | Arguments |
| --- | --- | --- | --- | --- |
| 1 | Clusters | `clusters/ClustersScreen.kt` `ClustersScreen` | `clusters` | — |
| 2 | Cluster summary | `clusters/ClusterSummaryScreen.kt` `ClusterSummaryScreen` | `cluster/{profileId}` | `profileId: String` |
| 3 | Browse (catalog) | `browse/BrowseScreen.kt` `BrowseScreen` | `catalog` | — |
| 4 | Resource list | `browse/ResourceListScreen.kt` `ResourceListScreen` | `browse/{resourceKey}` | `resourceKey: String` |
| 5 | Object detail | `detail/ObjectDetailScreen.kt` `ObjectDetailScreen` | `object/{resourceKey}/{namespace}/{name}` | `resourceKey`, `namespace`, `name` — all `String` |
| 6 | Logs | `detail/LogsScreen.kt` `LogsScreen` | `logs/{namespace}/{pod}?container={container}&kind={kind}` | `namespace`, `pod` required; `container` and `kind` nullable, default `null` |
| 7 | Terminal | `detail/TerminalScreen.kt` `TerminalScreen` | `terminal/{namespace}/{pod}?container={container}` | `namespace`, `pod` required; `container` nullable, default `null` |
| 8 | Port forward | `detail/PortForwardScreen.kt` `PortForwardScreen` | `forward/{namespace}/{pod}` | `namespace`, `pod` |
| 9 | GitOps (Helm / Flux / Argo CD) | `helm/HelmScreen.kt`, `flux/FluxScreen.kt`, `argo/ArgoScreen.kt`, hosted by `GitOpsScreen` | `gitops?tab={tab}` | `tab` nullable `String` (`helm` \| `flux` \| `argo`) |
| 10 | Helm release detail | `helm/HelmDetailScreen.kt` `HelmDetailScreen` | `helm/{namespace}/{name}` | `namespace`, `name` |
| 11 | Events | `events/EventsScreen.kt` `EventsScreen` | `events` | — |
| 12 | Settings | `settings/SettingsScreen.kt` `SettingsScreen` | `settings` | — |

Destination #9 is one navigation destination but renders one of three dashboards: `GitOpsScreen`
uses the shared responsive `TabStrip` above the `HelmScreen`, `FluxScreen` and `ArgoScreen` bodies.
The same component is used by Object detail and Helm release detail; it switches between evenly
distributed and horizontally scrollable Material tabs as space and font scale require. The bottom
bar's GitOps item always lands on the concrete route `Routes.gitops(GitOpsTab.HELM)`
(`gitops?tab=helm`), not on the unfilled `?tab={tab}` pattern, so the navigation match cannot fail.

### Route helpers

From `Routes`:

```kotlin
fun resourceList(key: String)                                     // "browse/<key>"
fun clusterSummary(profileId: String)                             // "cluster/<profileId>"
fun objectDetail(key: String, namespace: String, name: String)    // "object/<key>/<ns>/<name>"
fun logs(namespace: String, pod: String, container: String? = null, kind: String? = null)
fun terminal(namespace: String, pod: String, container: String? = null)
fun portForward(namespace: String, pod: String)                   // "forward/<ns>/<pod>"
fun helmRelease(namespace: String, name: String)                  // "helm/<ns>/<name>"
fun gitops(tab: GitOpsTab = GitOpsTab.HELM)                       // "gitops?tab=<slug>"
```

All path segments and query values are `Uri.encode`d. `logs` builds its query from whichever of
`container`/`kind` are non-null and omits the `?` entirely when neither is set. `resourceKey` is
`group|version|plural|kind` (`UiSupport.routeKey()`), so `apps/v1/Deployment` becomes
`apps|v1|deployments|Deployment` and a core resource has an empty group field (e.g.
`|v1|pods|Pod`). Cluster-scoped objects pass `NO_NAMESPACE = "_none_"` as the namespace segment
(`ResourceListScreen`, `ObjectDetailScreen`, `FluxScreen`, `ArgoScreen`); `ObjectDetailScreen`
converts it back to `null` before use.

### Bottom bar

`TopDestination` (`ui/Navigation.kt`) defines five items. The bar remains visible on eight routes:
the two Clusters routes (`clusters`, `cluster/{profileId}`), the two Browse routes (`catalog`,
`browse/{resourceKey}`), GitOps, Helm release detail, Events and Settings. Object detail, logs,
terminal and port-forward are full-window pushed screens. The selected top tab stays lit on cluster
summary, resource list and Helm release detail.

| Label | Route family | Idle/selected icons |
| --- | --- | --- |
| Clusters | `clusters`, `cluster/{profileId}` | outlined/filled `Dns` |
| Browse | `catalog`, `browse/{resourceKey}` | outlined/filled `List` |
| GitOps | `gitops?tab=helm`, Helm release detail | outlined/filled `AccountTree` |
| Events | `events` | outlined/filled `Event` |
| Settings | `settings` | outlined/filled `Settings` |

`navigateToTop(route)` pops to the graph's start destination with `saveState`/`restoreState` and
`launchSingleTop`, so tapping a destination never stacks duplicates. Bottom-bar destinations use
`BottomBarScreenInsets` (status bar + horizontal cutout only); the root `Scaffold` owns the bottom
edge.

---

## 1. Clusters — `clusters`

**Shows.** The stored `ClusterProfile` list (`ClustersScreen`), one row per cluster with its
endpoint label (`host:port · namespace`), a connection chip (`Not connected`, `Connecting…`,
`Connected`, `Connection failed`), a one-word auth badge (`Cert`/`Token`/`Basic`/`None`), a TLS
warning chip when verification is disabled, and a `Warnings` chip when the profile carried an
`exec` command or an `auth-provider`. Two profiles that share a name get their `host:port` appended
to the headline. Empty state explains importing, pasting, or adding manually. `ImportSheet` shows
the parsed contexts with a per-context checkbox, `Select all` / `None`, the parser warnings, and a
confirm button labelled with the number to import.

**Actions.** Tapping a row connects (if needed) and **navigates to that cluster's summary**
(`cluster/{profileId}`) — not the catalog.

- *Import kubeconfig* — paste YAML or choose a file with `ActivityResultContracts.OpenDocument`.
  `ClustersViewModel.importUri` reads the stream via the content resolver and delegates to the same
  `importText` path as paste. Every context becomes a `ClusterProfile`; colliding names are
  disambiguated by appending `host:port` (`disambiguateNames`), and a cluster already stored by
  server keeps its existing name.
- *Add cluster manually* — dialog with name, server URL, namespace, and credential section (token,
  username/password, client certificate + key), optional custom CA, and a
  *Skip TLS certificate verification* switch. Name and server URL are required.
- Long-press (or the row's overflow menu): *Connect*, *Set as active*, *Edit namespace*,
  *Duplicate* (copies the profile with a new id and a ` copy` suffix), *Delete* (with confirm —
  removes credentials from the device only). A failed connect shows the error and a retry.

## 2. Cluster summary — `cluster/{profileId}`

The landing screen for a connected cluster: identity, live counts, resource usage and the handful
of things that are actually wrong. Reached by tapping a cluster; the bottom-bar Browse destination
still opens the full catalog. A deep link whose profile is not the live session reconnects in the
background before loading, so it never shows a stale cluster's numbers.

**Shows**, top to bottom, in one `LazyColumn`:

- **Header card** — profile name, `host:port`, a connection chip derived from `SessionState`, and
  the server version (`GET /version` → `gitVersion`, rendered `Kubernetes <version>`, else
  *Kubernetes version unavailable*).
- **Stat grid** — four tiles, each `value/total` or `—` when not computable: *Nodes ready*
  (`status.conditions[Ready]`), *Namespaces*, *Pods running* (`status.phase == Running`),
  *Deployments available* (`status.availableReplicas >= spec.replicas`).
- **Cluster usage** — CPU and memory used against allocatable, each as `used / total`, a
  percentage and a progress bar. Used is the sum of pod metrics (what workloads actually consume);
  the denominator is the sum of each node's `status.allocatable`. A missing metric renders `—`, not
  zero, and a percentage needs both sides.
- **Needs attention** — a green "everything looks healthy" row, or counts for pods not ready,
  deployments unavailable, Flux Kustomizations failing and Flux HelmReleases failing, plus the five
  most recent `Warning` events (reason, `Kind/name`, namespace, message) which open the Events feed.
  "All healthy" is only asserted once every source has answered.

**Where the numbers come from.** Nodes, namespaces, pods, `apps/deployments`, events,
`kustomize.toolkit.fluxcd.io/kustomizations` and `helm.toolkit.fluxcd.io/helmreleases` are read via
`KubeRepository.listAll`, which follows continuation tokens at 500 objects per page, up to 40 pages.
Health sources that fail or hit the page cap are reported as unavailable, not silently treated as
healthy. Metrics are read when discovery exposes `pods` under `metrics.k8s.io`.

**Honest degradation.** With metrics-server absent the Usage card prints *Metrics unavailable —
metrics-server not detected* plus a hint to install it. If the API is discovered but the metrics
request fails or is refused, it shows a separate request-failed state. A Flux object counts as
failing when its `Ready` condition is present and not `True` and it is not suspended; a suspended
object is never counted. Deployments intentionally scaled to zero count as available unless they
report `ReplicaFailure`.

**Actions.** Refresh (app-bar) and pull-to-refresh. Both re-run the full gather.

## 3. Browse — `catalog`

**Shows.** The API catalog for the connected cluster, resolved from the live discovery snapshot:

- **Namespace chip row** — one `AssistChip` showing the chosen namespace (or *All namespaces*) plus
  a hint with the number of namespaces visible. This is the namespace a resource list will inherit.
- **Search field** — matches kind, plural, group, short names and categories.
- **Scope filter** — `FilterChip`s for *All* / namespaced / cluster-scoped.
- **Groups filter** — a chip, labelled with the selected count when non-empty, opening a
  multi-select sheet with its own search and a *Clear* action.
- **Dashboards** — a grid (Helm, Flux, Argo CD, Events, Settings), shown only when nothing is
  filtered.
- **Recent** — the last six kinds opened on this cluster, newest first (shown only when the search
  box is empty).
- **Common** — a curated list of the plurals an engineer reaches for first (pods, deployments,
  statefulsets, daemonsets, replicasets, jobs, cronjobs, services, endpoints, ingresses, configmaps,
  secrets, PVCs, PVs, storageclasses, serviceaccounts, roles, rolebindings, clusterroles,
  clusterrolebindings, nodes, namespaces, events), resolved against the live catalog.
- **API groups** — one collapsible row per group (name plus a kind count), listing that group's
  resources in its preferred version; expansions are remembered across rotation.
- **Custom resources** — the catch-all list for non-core kinds that no API-group section covers
  (a group the server did not serve, or a non-preferred version), so no kind is unreachable.
- **Core v1** — every core resource.

When nothing matches, an empty state offers *Clear filters*. With no session, the connection gate
offers retry or a jump to Clusters.

**Actions.** Tap any resource row → `Routes.resourceList(resource.routeKey())`; opening a namespaced
kind also remembers Browse's current namespace for that kind, so its list opens where Browse was
scoped. Tap a dashboard tile → that destination.

## 4. Resource list — `browse/{resourceKey}`

**Shows.** A `LazyColumn` of objects for the resolved resource, each row a health chip, name, and a
secondary line (namespace when the scope is cluster-wide or the resource is cluster-scoped, health
detail, age, and — for pods presented by metrics-server — a CPU/memory segment). The header line
spells out the scope (namespace, *All namespaces*, or *cluster-scoped*) and the count, the visible
subset when a name filter narrows it, and *Loading more…* when the API returned a `continue` token
(the first 500 objects are shown — the note flags truncation; there is no load-more action).
When a cluster-wide list is refused by RBAC the list is fetched namespace by namespace and a banner
(`list_partial`) explains the partial result. Pull to refresh is available. Empty states
distinguish "no objects in scope" (with an *All namespaces* escape hatch) from "no matches" (with a
*Clear* action).

**Actions.**

- Namespace button (namespaced resources only) — a sheet listing every visible namespace plus
  *All namespaces*. Namespaced resources start in the profile's namespace (never an accidental
  cluster-wide read), and the choice is remembered per cluster and resource in memory.
- Filter button — toggles a label-selector field (placeholder `app=nginx,tier=web`).
- Refresh, search-by-name filter, and pull-to-refresh.
- Row tap → object detail. Long press (or the row's more-actions button) → *Summary* (open detail),
  *Scale* (kinds in `SCALABLE = {Deployment, StatefulSet, ReplicaSet, ReplicationController}`),
  *Rollout restart* (`RESTARTABLE = {Deployment, StatefulSet, DaemonSet}`), *Delete*. Deleting a
  `Secret`, `PersistentVolumeClaim`, `Namespace` or `PersistentVolume` requires typing the object's
  name to confirm.
- Create FAB (when the resource supports `create`) — a YAML editor seeded with a minimal manifest
  generated for that kind (`UiSupport.minimalManifest`), submitted as a POST.

Pod CPU/memory is fetched once per load with `Metrics.podMetrics` and keyed `namespace/name`; an
absent metrics-server leaves the map empty and rows render without a usage segment. A request
failure is shown in a separate error banner rather than confused with an absent metrics API.

**Shows.** The app-bar subtitle gives kind and namespace. Below it is a header strip with the health
chip, staleness note when `metadata.generation` is ahead of `status.observedGeneration`, age and
generation, then the shared responsive tab strip. Tab order follows the kind: a Pod gets
`Summary | YAML | Events | Containers | Controller`; a Deployment gets
`Summary | YAML | Events | Pods | Controller | ReplicaSets`; a CronJob gets
`Summary | YAML | Events | Pods | Controller | Jobs`; a Node gets
`Summary | YAML | Events | Pods | Controller`; other kinds get
`Summary | YAML | Events | Controller`.

**Tabs.** Every kind gets `Summary | YAML | Events | Controller`; the rest appear only when the
kind has them and the tab would render something meaningful (an empty tab is never offered). If the
object is re-kinded while open, the selection falls back to Summary.

| Tab | When | Contents |
| --- | --- | --- |
| Summary | always | Health, kind-specific status rows (`Status.detailRows`), conditions, metadata (name, namespace, API version, UID, resource version, created, generation), labels and annotations — all copyable. |
| YAML | always | The object as YAML, read-only until *Edit*. |
| Events | always | Events for this object (`involvedObject.kind`/`involvedObject.name` field selectors). |
| Pods | `Deployment`, `ReplicaSet`, `ReplicationController`, `StatefulSet`, `DaemonSet`, `Job`, `CronJob`, `Node` | The pods this object owns: readiness chip, phase, restarts, node, age, with *Logs* and *Shell* buttons; tapping opens the pod. For a Node the pods are found by the server-side field selector `spec.nodeName`; otherwise through the shared resolver (`WorkloadPods.kt`). |
| Containers | `Pod` | Per container: name, image, ready, restarts, state (`Waiting`/`Terminated`/`Running`/`Unknown`) with reason/exit code, start time, declared ports, and *Logs*/*Shell* buttons. |
| Controller | always | The controlling spec (see below), owner references (clickable when the owner kind resolves in the catalog), and any `created-by` annotation. Empty state when the object declares none of these. |
| ReplicaSets | `Deployment` | The Deployment's ReplicaSet revision history, newest first. |
| Jobs | `CronJob` | The Jobs the CronJob owns, newest first. |

**Controller spec rows** (`ObjectDetailViewModel.controllerRows`), only the fields that exist:

- `Deployment` — strategy type, max surge, max unavailable, revision history limit, progress
  deadline, minimum ready seconds.
- `StatefulSet` — update strategy type, revision history limit, pod management policy, minimum
  ready seconds.
- `DaemonSet` — update strategy type, max unavailable, revision history limit, minimum ready
  seconds.
- `ReplicaSet`, `ReplicationController` — revision history limit.
- `Job` — completions, parallelism, backoff limit.
- `CronJob` — schedule, concurrency policy, suspend, and the job template's completions/parallelism.
- `Node` — schedulable, and every taint as `key=value:Effect` (or *None*).
- Argo CD `Application` — sync policy (*Auto-sync* or *Manual*) and the current `operationState`
  phase/message.
- Flux kinds — suspend, interval, prune (when declared) and target namespace.
- Every kind additionally gets its selector when `spec.selector.matchLabels` exists.

**ReplicaSets tab.** The Deployment's ReplicaSets selected by its own `matchLabels`, each showing
revision, name, `desired · current · ready`, the first container image and age. The row whose
revision equals the Deployment's live `deployment.kubernetes.io/revision` annotation is tinted and
chipped *Current* — so the highlight follows the real rollout, not whichever ReplicaSet is newest.
Tapping a row opens that ReplicaSet.

**Jobs tab.** Each Job the CronJob owns: name, a chip (*Complete* / *Running* / its age), and
active/succeeded/failed counts. Tapping opens the Job.

**Actions.** Refresh. Overflow menu, conditioned on kind and the resource's supported verbs:

- *Logs* for a Pod (`resource.supports("get")`), and for a workload in `WORKLOAD_LOG_KINDS` the
  menu offers *Logs* only (merged; shell and port-forward stay pod-only). The route carries
  `kind = <kind>` for a workload so the logs screen enters merged mode.
- *Shell* and *Port-forward* for a Pod.
- *Scale* (`SCALABLE`, `update`); *Rollout restart* (`RESTARTABLE`, `patch`); *Rollout undo* for a
  Deployment (a revision picker of ReplicaSet history plus "the revision before the current one").
- *Reconcile* / *Suspend* / *Resume* (Flux groups `kustomize.toolkit.fluxcd.io`,
  `helm.toolkit.fluxcd.io`, `source.toolkit.fluxcd.io`).
- *Refresh*, *Hard refresh*, *Sync* and *Terminate* (Argo CD `Application`).
- *Cordon* / *Uncordon* and *Drain* for a Node (see below).
- *Delete* (typing the name for Secret, PVC, PV or Namespace).

The YAML tab offers *Edit*, *Apply* (a PUT to the same name — a document that renames or re-kinds
the object is refused with a mismatch warning), *Cancel*, *Copy* and *Share*.

**Node operations.**

- *Cordon*/*Uncordon* — the label follows the live spec, so it states what the tap will do. It
  merge-patches `spec.unschedulable` (`GitOps.setNodeSchedulable`); the Summary health then reads
  *Ready,SchedulingDisabled*.
- *Drain* — a destructive menu action opens a dialog offering *Also evict DaemonSet pods*. It lists
  the node's pods and uses the `policy/v1` Eviction subresource, so PodDisruptionBudgets are
  honoured: a protected pod can refuse eviction and the API server's message is surfaced. The code
  skips DaemonSet-owned pods unless the option is enabled, and skips Node-owned mirror pods. Drain
  does **not** cordon the node; completion reports the number of pods evicted.

## 6. Logs — `logs/{namespace}/{pod}?container={container}&kind={kind}`

**Shows.** The log tail as a scrollable list, colour-coded for lines containing `ERROR`/`FATAL`/
`panic`/`Exception` (severity 2) or `WARN`/`WARNING` (severity 1). Title shows the target name and a
subtitle with namespace, container (hidden when lines are tagged), any merge-cap note, streaming
state and *Auto-scroll paused* when the reader has scrolled back. Up to 4000 lines are kept in
memory. A *Jump to latest* chip appears when auto-scroll is paused.

**Single pod vs workload.** When `kind` is one of `WORKLOAD_LOG_KINDS` = {`Deployment`,
`StatefulSet`, `DaemonSet`, `ReplicaSet`, `ReplicationController`, `Job`, `CronJob`}, the screen
enters merged mode:

- The workload's pods are resolved with the shared `WorkloadPods.kt` resolver (selector first,
  `ownerReferences` fallback, one level through ReplicaSets/Jobs) and shown as a horizontally
  scrollable chip row: *All pods* plus one chip per pod, each with a tone dot (Running+ready = OK,
  Running = WARN, otherwise BAD).
- With *All pods* selected, `Logs.streamMerged` follows up to `MAX_MERGED_PODS = 20` pods at once
  and prefixes every line with `pod | `; a note above the list reports *Showing X of Y pods* when
  the cap truncates. Selecting a single pod streams just that pod, untagged.
- The options sheet states that a merged view always reads every container of each pod, so the
  container picker is hidden rather than silently dropping containers.
- A workload that currently owns no pods shows an explicit empty state instead of a silent stream.

**Actions.** Stream options sheet: container (only when the target has more than one), tail size
(`100`, `500`, `1000`, `5000`), *Timestamps*, *Follow*, *Previous container*. Refresh restarts the
stream; the overflow offers *Copy whole log*, *Share log* and *Stop*/*Start*. Leaving the screen
stops the stream. Options are remembered in the view model's `SavedStateHandle`, so they survive
rotation.

## 7. Terminal — `terminal/{namespace}/{pod}?container={container}`

**Shows.** A monospaced scrollback of 2000 lines, with the shell banner (`$ <command>`) and a status
line (`Opening shell…`, connected, *Session closed*, error). When a session ends with no output the
screen explains that distroless/scratch images ship no shell at the requested path and shows the API
server's message.

**Actions.** Control bar with the command field (`Send`) and Ctrl-C, Escape and Tab buttons. Stream
options sheet: shell (`/bin/sh`, `/bin/bash`, `/bin/ash`), container (when more than one), and an
*Allocate TTY* switch. Reconnect. Changing container, shell or TTY reconnects immediately. Terminal
resize is forwarded to the server as it changes.

## 8. Port forward — `forward/{namespace}/{pod}`

**Shows.** Active forwards as cards (local `127.0.0.1:<port>` → `pod:<remotePort>`, container,
protocol, up/down byte counters polled once per second, and any per-connection failure message), and
below them the pod's declared container ports with a *Forward* button each. If the pod declares no
ports it says so and points at the custom port action. A copy button copies `127.0.0.1:<localPort>`.

**Actions.** *Forward* on any declared port; *Custom remote port* dialog for an undeclared port;
*Stop* per forward. Every forward is bound to a loopback listener before the tunnel opens, so the
local port is shown immediately. All forwards are stopped when the screen is left. Starting the same
remote port twice is refused with a message.

## 9. GitOps — `gitops?tab={tab}` (default `helm`)

One app bar and a three-way segmented control; `GitOpsTab.fromSlug` maps `helm`, `flux`, `argo` and
falls back to Helm for a missing or unknown slug.

### Helm tab — `helm/HelmScreen.kt`

**Shows.** Releases with chart/app version, revision, namespace and status, plus a scope strip
summarising which namespaces are searched (*All releases* when none are selected). Empty state
explains that release secrets (`owner=helm`) and Tiller config maps were searched.

**Actions.** Search (name, chart, namespace, app version), namespace scope sheet, refresh, and tap a
release → `Routes.helmRelease(namespace, name)`.

### Flux tab — `flux/FluxScreen.kt`

**Shows.** Summary tiles with a ready count, then sections for Kustomizations, HelmReleases and
Sources (GitRepository, OCIRepository, HelmRepository, Bucket), each row showing health, name,
namespace, revision, URL/source ref, interval and a *Suspended* marker. When none of the six Flux
CRDs resolve, a panel names the CRDs that were probed.

**Actions.** Refresh; per row, *Reconcile* (with an *Also reconcile the source* option) and
*Suspend*/*Resume*; tap a row → object detail.

### Argo CD tab — `argo/ArgoScreen.kt`

**Shows.** Applications and ApplicationSets, each row with health × sync, target revision,
destination and last operation, and an *Auto-sync* marker. A panel names
`applications.argoproj.io` and `applicationsets.argoproj.io` when Argo CD is absent.

**Actions.** Refresh; per row, *Refresh*, *Hard refresh*, *Sync* (dialog with optional revision,
*Prune resources no longer in Git*, *Dry run*), *Terminate*; tap a row → object detail.

## 10. Helm release detail — `helm/{namespace}/{name}`

**Shows.** The release header (health chip, app version, last-deployed time, description), then
tabs:

- **Overview** — chart, version, app version, revision, namespace, updated, source, name, chart
  values and user values, plus a defaults-vs-overrides diff.
- **Notes** — the release's stored notes.
- **Manifest** — the manifest with a searchable list of the resources it contains
  (`kind/name`, apiVersion).
- **History** — every stored revision, newest first.

**Actions.** History picker (enabled when there is more than one revision) to view an earlier
revision; *Copy manifest*; *Share manifest*; *Uninstall*, with an option to *Also delete the
Kubernetes resources created by this release* — the dialog states that Helm hooks are not re-run and
that deletion follows the newest revision's manifest. Uninstall reports any per-object problems
afterwards.

## 11. Events — `events`

**Shows.** The cluster event feed, newest first (`lastTimestamp`, else `eventTime`, else
`creationTimestamp`), each row showing type (`Normal`/`Warning`), reason, involved `Kind/name`,
namespace, count and message. Grouped by involved object (most eventful objects first) by default.

**Actions.** A `Normal`/`Warning` chip pair (warnings-only), an `AssistChip` toggling
grouped-by-involved-object ⇄ chronological by time, a namespace scope sheet built from the
namespaces already in the feed (no extra API call — falling back to the cluster's namespace list
when the feed has none), *All namespaces*, refresh, pull-to-refresh, and *Copy* (one line per
visible event) in the app bar.

## 12. Settings — `settings`

**Shows.** Cluster information (name, server, namespace, auth kind, context, source, TLS
verification state, and `tls-server-name`/`proxy-url` when set), a *Skip TLS verification* switch, a
warning card when the profile's kubeconfig used an `exec` credential plugin (naming the command) or
an `auth-provider`, diagnostics (`<n> API groups · <n> resource kinds`, `<n> namespaces visible`),
and an *About* card explaining that credentials are AES-GCM encrypted with an Android Keystore key
and never leave the device except as `Authorization` headers.

**Actions.** *Reload discovery* (forced re-fetch and rebuild of the client), *Forget cluster* (with
confirm; removes stored credentials and cached discovery for this cluster, and disconnects if it is
active), and the TLS switch, which rebuilds the connection because the trust manager changed.
