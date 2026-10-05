import { Activity, Gauge, GitBranch, Layers, Search, SquareTerminal } from "lucide-react"
import type { FeatureIcon as FeatureIconName } from "../config/site"

const icons = {
  clusters: Layers,
  browse: Search,
  health: Activity,
  pod: SquareTerminal,
  gitops: GitBranch,
  metrics: Gauge,
} satisfies Record<FeatureIconName, unknown>

export function FeatureIcon({ name }: { name: FeatureIconName }) {
  const Icon = icons[name]
  return <Icon aria-hidden="true" className="size-5" strokeWidth={1.75} />
}
