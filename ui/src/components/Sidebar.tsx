import type { LucideIcon } from 'lucide-react';
import { Button } from '@/components/ui/button';
import {
  Activity,
  BookOpen,
  ChartNoAxesCombined,
  ClipboardCheck,
  KeyRound,
  LayoutGrid,
  Settings2,
  ShieldCheck,
  Users,
} from 'lucide-react';

export type NavKey = 'overview' | 'users' | 'tenants' | 'security' | 'audit' | 'settings';

type NavItem = {
  key: NavKey;
  label: string;
  icon: LucideIcon;
};

const primaryItems: NavItem[] = [
  { key: 'overview', label: '总览', icon: LayoutGrid },
  { key: 'users', label: '设备', icon: Users },
  { key: 'tenants', label: '项目', icon: ChartNoAxesCombined },
  { key: 'security', label: '告警', icon: ShieldCheck },
  { key: 'audit', label: '运行日志', icon: ClipboardCheck },
];
const utilityItems: NavItem[] = [
  { key: 'settings', label: '个人设置', icon: Settings2 },
];

type SidebarProps = {
  activeKey: NavKey;
  onNavigate: (key: NavKey) => void;
};

export function Sidebar({ activeKey, onNavigate }: SidebarProps) {
  return (
    <aside className="sidebar" aria-label="主导航">
      <nav className="sidebar-nav">
        {primaryItems.map((item) => (
          <SidebarItem key={item.key} item={item} active={activeKey === item.key} onClick={() => onNavigate(item.key)} />
        ))}
      </nav>
      <div className="sidebar-bottom">
        <SidebarItem
          item={utilityItems[0]}
          active={activeKey === utilityItems[0].key}
          onClick={() => onNavigate(utilityItems[0].key)}
        />
        <Button variant="ghost" size="icon" className="sidebar-item" type="button" aria-label="帮助中心" title="帮助中心">
          <BookOpen size={19} strokeWidth={1.8} />
        </Button>
      </div>
    </aside>
  );
}

function SidebarItem({ item, active, onClick }: { item: NavItem; active: boolean; onClick: () => void }) {
  const Icon = item.icon;
  return (
    <Button
      variant="ghost"
      size="icon"
      className={`sidebar-item${active ? ' sidebar-item-active' : ''}`}
      type="button"
      aria-label={item.label}
      aria-current={active ? 'page' : undefined}
      title={item.label}
      onClick={onClick}
    >
      <Icon size={19} strokeWidth={active ? 2.1 : 1.8} />
      <span className="sidebar-tooltip">{item.label}</span>
    </Button>
  );
}

export function BrandMark() {
  return (
    <div className="brand-mark" aria-hidden="true">
      <Activity size={19} strokeWidth={2.1} />
    </div>
  );
}

export function SecurityMark() {
  return <KeyRound size={15} strokeWidth={1.9} />;
}
