import type { LucideIcon } from 'lucide-react';
import { ArrowDownRight, ArrowUpRight } from 'lucide-react';

export type MetricTone = 'blue' | 'violet' | 'amber' | 'green';

type MetricCardProps = {
  label: string;
  value: string;
  change: string;
  description: string;
  icon: LucideIcon;
  tone: MetricTone;
  positive?: boolean;
};

export function MetricCard({ label, value, change, description, icon: Icon, tone, positive = true }: MetricCardProps) {
  return (
    <article className="metric-card">
      <div className="metric-card-topline">
        <span className={`metric-icon metric-icon-${tone}`}><Icon size={17} strokeWidth={1.9} /></span>
        <span className="metric-label">{label}</span>
        <span className={`metric-change${positive ? '' : ' metric-change-negative'}`}>
          {positive ? <ArrowUpRight size={14} /> : <ArrowDownRight size={14} />}
          {change}
        </span>
      </div>
      <div className="metric-value">{value}</div>
      <div className="metric-description">{description}</div>
    </article>
  );
}
