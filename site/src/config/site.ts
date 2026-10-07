export const repository = "https://github.com/rinci-labs/kubedeck"

// Resolved at build time so download links follow the newest GitHub Release
// (published by .github/workflows/release.yml). Falls back to the first release offline.
const fallbackVersion = "0.1.0"

const releaseTag = (tag: string | null | undefined) =>
  tag && /^v\d+\.\d+\.\d+$/.test(tag) ? tag.slice(1) : null

// Shared CI builders often hit the unauthenticated API rate limit, so the API is tried first and the
// web redirect (`/releases/latest` -> `/releases/tag/vX.Y.Z`), which is not rate limited, second.
async function fromApi(): Promise<string | null> {
  const response = await fetch("https://api.github.com/repos/rinci-labs/kubedeck/releases/latest", {
    headers: { Accept: "application/vnd.github+json" },
    signal: AbortSignal.timeout(10_000),
  })
  if (!response.ok) return null
  const { tag_name: tag } = (await response.json()) as { tag_name?: string }
  return releaseTag(tag)
}

async function fromRedirect(): Promise<string | null> {
  const response = await fetch(`${repository}/releases/latest`, {
    redirect: "manual",
    signal: AbortSignal.timeout(10_000),
  })
  return releaseTag(response.headers.get("location")?.split("/").pop())
}

async function latestReleaseVersion(): Promise<string> {
  for (const source of [fromApi, fromRedirect]) {
    try {
      const version = await source()
      if (version) return version
    } catch {
      // try the next source
    }
  }
  return fallbackVersion
}

export const version = await latestReleaseVersion()
export const isFirstRelease = version === fallbackVersion
export const releaseUrl = `${repository}/releases/tag/v${version}`
export const apkUrl = `${repository}/releases/download/v${version}/KubeDeck-v${version}.apk`
export const featuresDocUrl = `${repository}/blob/main/docs/FEATURES.md`

export const navLinks = [
  { label: "Features", href: "#features" },
  { label: "Security", href: "#security" },
  { label: "GitHub", href: repository, external: true },
]

export type FeatureIcon = "clusters" | "browse" | "health" | "pod" | "gitops" | "metrics"

export interface Feature {
  icon: FeatureIcon
  title: string
  description: string
  tags?: string[]
}

export const features: Feature[] = [
  {
    icon: "pod",
    title: "Get closer to the pod",
    description: "Follow container logs, open an interactive exec session, or forward a pod port to your device over the Kubernetes API.",
    tags: ["Logs", "Shell", "Port-forward"],
  },
  {
    icon: "clusters",
    title: "Bring every cluster",
    description: "Import a kubeconfig and review its contexts before adding them, or enter a cluster by hand.",
  },
  {
    icon: "browse",
    title: "Find what’s running",
    description: "Browse resources from live API discovery, including namespaced kinds, cluster-wide kinds, and CRDs.",
  },
  {
    icon: "gitops",
    title: "Keep GitOps in view",
    description: "Inspect Helm release history and values, reconcile Flux resources, and refresh or sync Argo CD applications.",
    tags: ["Helm", "Flux", "Argo CD"],
  },
  {
    icon: "health",
    title: "Follow workload health",
    description: "See status, conditions, events, and pod metrics. Read YAML and owner relationships when a resource needs a closer look.",
  },
  {
    icon: "metrics",
    title: "Use the cluster signals",
    description: "Check node capacity and usage when metrics-server is available. Missing metrics stay visibly unavailable, not zero.",
  },
]

export const securityPoints = [
  {
    title: "Encrypted on device",
    description: "Saved profiles are encrypted with AES-256-GCM using an Android Keystore key and stored locally.",
  },
  {
    title: "Straight to your API server",
    description: "Requests go to the Kubernetes API server with your configured credentials and cluster TLS settings.",
  },
  {
    title: "Bound by RBAC",
    description: "Every action runs with your existing cluster access. Nothing is granted beyond it.",
  },
]

export const limitations = "Kubeconfig exec plugins and interactive OIDC login or refresh are not supported. Cleartext HTTP API servers are blocked, so use HTTPS."
