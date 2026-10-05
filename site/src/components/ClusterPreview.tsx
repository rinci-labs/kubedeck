import { Badge } from "@almach/ui/badge"
import { Card } from "@almach/ui/card"
import { Progress } from "@almach/ui/progress"
import { AlertTriangle, Bell, Boxes, Layers, RefreshCw, Search } from "lucide-react"
import { useState, type ComponentType } from "react"

// Sample data laid out like the app's Cluster summary, resource list and Events screens
// (docs/FEATURES.md). Values are illustrative and kept consistent across tabs.
const stats = [
  { label: "Nodes ready", value: "3", total: "3" },
  { label: "Namespaces", value: "12" },
  { label: "Pods running", value: "46", total: "48" },
  { label: "Deployments available", value: "18", total: "19" },
]

const usage = [
  { label: "CPU", detail: "2.4 / 8 cores", value: 30 },
  { label: "Memory", detail: "9.1 / 24 GiB", value: 38 },
]

const pods = [
  { name: "checkout-7c9f6d8b5-x2lqp", namespace: "shop", status: "Running", restarts: 0 },
  { name: "payments-5b8d4c7f9-k8wzn", namespace: "shop", status: "CrashLoopBackOff", restarts: 7 },
  { name: "search-6f7c9b5d4-r4tnm", namespace: "shop", status: "Running", restarts: 0 },
  { name: "worker-84d6c5f7b-pq9vd", namespace: "jobs", status: "Pending", restarts: 0 },
  { name: "gateway-9d5b7c6f8-m3hxj", namespace: "edge", status: "Running", restarts: 1 },
]

const events = [
  { reason: "BackOff", object: "Pod/payments-5b8d4c7f9-k8wzn", namespace: "shop", message: "Back-off restarting failed container" },
  { reason: "FailedScheduling", object: "Pod/worker-84d6c5f7b-pq9vd", namespace: "jobs", message: "0/3 nodes are available: 3 Insufficient memory." },
  { reason: "ProgressDeadlineExceeded", object: "Deployment/payments", namespace: "shop", message: "Rollout exceeded its progress deadline" },
]

type TabId = "clusters" | "browse" | "events"

const tabs: { id: TabId; label: string; title: string; icon: ComponentType<{ className?: string }> }[] = [
  { id: "clusters", label: "Clusters", title: "Cluster", icon: Layers },
  { id: "browse", label: "Browse", title: "Pods", icon: Boxes },
  { id: "events", label: "Events", title: "Events", icon: Bell },
]

const statusVariant = (status: string) =>
  status === "Running" ? "success" : status === "Pending" ? "warning" : "destructive"

function SummaryView() {
  return (
    <>
      <Card className="p-4">
        <div className="flex items-center justify-between gap-2">
          <span className="text-sm font-semibold">staging</span>
          <Badge variant="success" size="sm">Connected</Badge>
        </div>
        <p className="mt-1 truncate font-mono text-[11px] text-muted-foreground">api.staging.internal:6443</p>
        <p className="mt-0.5 text-[11px] text-muted-foreground">Kubernetes v1.31.2</p>
      </Card>

      <div className="grid grid-cols-2 gap-2">
        {stats.map((stat) => (
          <Card key={stat.label} variant="muted" className="p-3">
            <p className="font-mono text-base font-medium">
              {stat.value}
              {stat.total && <span className="text-muted-foreground">/{stat.total}</span>}
            </p>
            <p className="mt-0.5 text-[10px] leading-tight text-muted-foreground">{stat.label}</p>
          </Card>
        ))}
      </div>

      <Card className="space-y-3 p-4">
        <span className="text-xs font-medium text-muted-foreground">Cluster usage</span>
        {usage.map((item) => (
          <div key={item.label} className="space-y-1.5">
            <div className="flex justify-between text-[11px]">
              <span className="font-medium">{item.label}</span>
              <span className="font-mono text-muted-foreground">{item.detail}</span>
            </div>
            <Progress aria-label={`${item.label} usage`} value={item.value} variant="success" size="sm" />
          </div>
        ))}
      </Card>

      <Card className="space-y-2 p-4">
        <span className="text-xs font-medium text-muted-foreground">Needs attention</span>
        {["2 pods not ready", "1 deployment unavailable"].map((item) => (
          <p key={item} className="flex items-center gap-2 text-[11px]">
            <AlertTriangle aria-hidden="true" className="size-3.5 text-warning" />
            {item}
          </p>
        ))}
      </Card>
    </>
  )
}

function PodsView() {
  return (
    <>
      <div className="flex items-center gap-2 rounded-md border border-border bg-card px-3 py-2 text-[11px] text-muted-foreground">
        <Search aria-hidden="true" className="size-3.5" />
        Filter pods in all namespaces
      </div>
      <Card className="divide-y divide-border">
        {pods.map((pod) => (
          <div key={pod.name} className="space-y-1.5 px-4 py-3">
            <p className="truncate font-mono text-[11px] font-medium">{pod.name}</p>
            <div className="flex items-center justify-between gap-2 text-[10px] text-muted-foreground">
              <span>{pod.namespace} · {pod.restarts} restarts</span>
              <Badge variant={statusVariant(pod.status)} size="sm">{pod.status}</Badge>
            </div>
          </div>
        ))}
      </Card>
    </>
  )
}

function EventsView() {
  return (
    <div className="space-y-2">
      {events.map((event) => (
        <Card key={event.object} className="space-y-1 p-4">
          <div className="flex items-center gap-2">
            <AlertTriangle aria-hidden="true" className="size-3.5 shrink-0 text-warning" />
            <span className="truncate text-[11px] font-semibold">{event.reason}</span>
          </div>
          <p className="truncate font-mono text-[10px] text-muted-foreground">{event.object} in {event.namespace}</p>
          <p className="text-[11px] leading-snug">{event.message}</p>
        </Card>
      ))}
    </div>
  )
}

const views: Record<TabId, ComponentType> = { clusters: SummaryView, browse: PodsView, events: EventsView }

export default function ClusterPreview() {
  const [active, setActive] = useState<TabId>("clusters")
  const current = tabs.find((tab) => tab.id === active)!
  const View = views[active]

  return (
    <div className="mx-auto w-full max-w-[320px] rounded-[2.75rem] border border-border bg-card p-2.5 shadow-2xl shadow-foreground/10">
      <div className="flex h-[660px] flex-col overflow-hidden rounded-[2.25rem] bg-background">
        <div className="mx-auto mt-3 h-5 w-20 shrink-0 rounded-full bg-muted" />

        <div className="flex shrink-0 items-center justify-between px-5 pb-3 pt-4">
          <span className="text-lg font-semibold tracking-tight">{current.title}</span>
          <RefreshCw aria-hidden="true" className="size-4 text-muted-foreground" />
        </div>

        <div
          key={active}
          id="preview-panel"
          role="tabpanel"
          aria-label={current.label}
          className="flex-1 space-y-3 overflow-hidden px-4 motion-safe:animate-rise"
        >
          <View />
        </div>

        <div role="tablist" aria-label="Preview screens" className="grid shrink-0 grid-cols-3 border-t border-border bg-card px-2 pb-4 pt-2">
          {tabs.map(({ id, label, icon: Icon }) => {
            const selected = id === active
            return (
              <button
                key={id}
                type="button"
                role="tab"
                aria-selected={selected}
                aria-controls="preview-panel"
                onClick={() => setActive(id)}
                className="group flex cursor-pointer flex-col items-center gap-1 rounded-md py-1.5 text-[10px] font-medium text-muted-foreground transition-colors hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring aria-selected:text-foreground"
              >
                <span
                  className={`grid h-7 w-12 place-items-center rounded-full transition-colors ${selected ? "bg-success-soft text-primary" : "group-hover:bg-muted"}`}
                >
                  <Icon className="size-4" />
                </span>
                {label}
              </button>
            )
          })}
        </div>
      </div>
    </div>
  )
}
