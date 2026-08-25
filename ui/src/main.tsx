import { useEffect, useState } from 'react';
import { createRoot } from 'react-dom/client';
import {
  Activity,
  ArrowRight,
  Bell,
  Check,
  Cpu,
  Database,
  KeyRound,
  Layers3,
  RadioTower,
  ShieldCheck,
  TriangleAlert,
} from 'lucide-react';
import { Button } from '@/components/ui/button';
import { getStoredUsername, hasSession, logout } from './auth';
import { ActivityTable, type ActivityItem } from './components/ActivityTable';
import { Header, SectionIcon, SparkleMark } from './components/Header';
import { Login } from './components/Login';
import { MetricCard } from './components/MetricCard';
import { PersonalSettings } from './components/PersonalSettings';
import { Sidebar, type NavKey, SecurityMark } from './components/Sidebar';
import './index.css';
import './styles.css';

const activityItems: ActivityItem[] = [
  { id: '1', action: '创建设备', subject: '温控器 · Developer', actor: '赵智', time: '刚刚', status: 'success' },
  { id: '2', action: '更新设备配置', subject: 'Roseboard Cloud / 生产区', actor: '陈默', time: '12 分钟前', status: 'success' },
  { id: '3', action: '轮换设备凭证', subject: 'Production Gateway', actor: '系统', time: '28 分钟前', status: 'pending' },
  { id: '4', action: '设备连接异常', subject: '未知设备 · 103.21.44.8', actor: '告警中心', time: '1 小时前', status: 'warning' },
  { id: '5', action: '发布固件版本', subject: 'roseboard-gateway v2.4.1', actor: '王洋', time: '昨天', status: 'success' },
];

const pageMeta: Record<NavKey, { eyebrow: string; title: string; description: string }> = {
  overview: { eyebrow: '物联网运营概览', title: '早上好，赵智', description: '这里是 Roseboard IoT 的实时运行概况。' },
  users: { eyebrow: '设备管理', title: '设备', description: '管理设备、连接状态和设备凭证。' },
  tenants: { eyebrow: '项目管理', title: '项目与租户', description: '管理项目空间、租户隔离和设备归属。' },
  security: { eyebrow: '风险与告警', title: '告警中心', description: '集中查看设备告警、认证风险和处理状态。' },
  audit: { eyebrow: '运行追踪', title: '运行日志', description: '追踪设备、遥测和平台配置的关键变更。' },
  settings: { eyebrow: '账户管理', title: '个人设置', description: '管理你的个人资料、安全设置和控制台偏好。' },
};

function App() {
  const [authenticated, setAuthenticated] = useState(() => hasSession());
  const [username, setUsername] = useState(() => getStoredUsername() ?? '管理员');
  const [activeKey, setActiveKey] = useState<NavKey>('overview');
  const [workspaceOpen, setWorkspaceOpen] = useState(false);
  const [profileOpen, setProfileOpen] = useState(false);
  const [darkMode, setDarkMode] = useState(false);

  useEffect(() => {
    document.documentElement.classList.toggle('dark', darkMode);
  }, [darkMode]);

  if (!authenticated) {
    return <Login onSuccess={(loggedInUsername) => { setUsername(loggedInUsername); setAuthenticated(true); }} />;
  }

  const meta = pageMeta[activeKey];

  function navigate(key: NavKey) {
    setActiveKey(key);
    setWorkspaceOpen(false);
    setProfileOpen(false);
  }

  function handleLogout() {
    logout();
    setProfileOpen(false);
    setAuthenticated(false);
  }

  return (
    <div className={`app-shell${darkMode ? ' theme-dark' : ''}`}>
      <Header
        username={username}
        workspaceOpen={workspaceOpen}
        profileOpen={profileOpen}
        darkMode={darkMode}
        onWorkspaceToggle={() => { setWorkspaceOpen((open) => !open); setProfileOpen(false); }}
        onProfileToggle={() => { setProfileOpen((open) => !open); setWorkspaceOpen(false); }}
        onThemeToggle={() => setDarkMode((dark) => !dark)}
        onOpenSettings={() => navigate('settings')}
        onLogout={handleLogout}
      />
      <div className="app-body">
        <Sidebar activeKey={activeKey} onNavigate={navigate} />
        <main className="main-content" id="overview">
          <div className="content-wrap">
            <section className="page-heading">
              <div>
                <div className="eyebrow"><span className="eyebrow-line" />{meta.eyebrow}</div>
                <h1>{meta.title}</h1>
                <p>{meta.description}</p>
              </div>
              <div className="heading-actions">
                {activeKey !== 'settings' && <><Button variant="outline" className="secondary-button" type="button"><Bell data-icon="inline-start" />通知 <span className="notification-count">3</span></Button><Button className="primary-button" type="button"><span>创建资源</span><ArrowRight data-icon="inline-end" /></Button></>}
              </div>
            </section>

            {activeKey === 'overview' ? <Overview /> : activeKey === 'settings' ? <PersonalSettings username={username} darkMode={darkMode} onThemeToggle={() => setDarkMode((dark) => !dark)} onLogout={handleLogout} /> : <ModuleSection moduleKey={activeKey} title={meta.title} description={meta.description} />}
          </div>
        </main>
      </div>
    </div>
  );
}

function Overview() {
  return (
    <>
      <section className="hero-banner">
        <div className="hero-copy">
          <div className="hero-kicker"><SparkleMark /> IoT 运营助手</div>
          <h2>设备运行如常，<br /><span>数据持续流动。</span></h2>
          <p>过去 24 小时内，设备连接保持稳定。<br />有 3 项设备告警等待处理。</p>
          <button className="hero-link" type="button">查看设备告警 <ArrowRight size={15} /></button>
        </div>
        <div className="hero-visual" aria-hidden="true">
          <div className="orb orb-one" />
          <div className="orb orb-two" />
          <div className="visual-grid" />
          <div className="hero-signal signal-one"><span /><b>99.98%</b><small>平台可用性</small></div>
          <div className="hero-signal signal-two"><span /><b>2,847</b><small>设备在线</small></div>
        </div>
      </section>

      <section className="section-block">
        <div className="section-heading"><div><h2>物联网指标</h2><p>平台过去 30 天的设备与数据运行数据</p></div><button className="text-button" type="button">查看运行报告 <ArrowRight size={14} /></button></div>
        <div className="metrics-grid">
          <MetricCard label="活跃设备" value="2,847" change="12.8%" description="较上月增长" icon={Cpu} tone="blue" />
          <MetricCard label="遥测消息" value="1.24M" change="8.4%" description="较上月增长" icon={RadioTower} tone="violet" />
          <MetricCard label="未处理告警" value="03" change="24.5%" description="较上月减少" icon={TriangleAlert} tone="amber" positive={false} />
          <MetricCard label="平台可用性" value="99.98%" change="0.02%" description="过去 30 天" icon={Database} tone="green" />
        </div>
      </section>

      <section className="dashboard-columns">
        <div className="section-block activity-block">
          <div className="section-heading"><div><h2>设备活动</h2><p>设备、遥测和平台的最新操作</p></div><button className="text-button" type="button">全部活动 <ArrowRight size={14} /></button></div>
          <ActivityTable items={activityItems} />
        </div>
        <div className="section-block security-block">
          <div className="section-heading"><div><h2>平台健康</h2><p>实时运行状态</p></div><SectionIcon><SecurityMark /></SectionIcon></div>
          <div className="security-status"><span className="security-check"><Check size={17} /></span><div><strong>设备平台健康</strong><span>连接、遥测和核心服务正常</span></div><span className="healthy-label">良好</span></div>
          <div className="security-list">
            <SecurityRow icon={KeyRound} label="设备认证" detail="设备凭证均已加密" />
            <SecurityRow icon={ShieldCheck} label="告警策略" detail="2 项策略需要复核" warning />
            <SecurityRow icon={Layers3} label="数据备份" detail="最近备份：今天 03:00" />
          </div>
          <button className="security-link" type="button">进入告警中心 <ArrowRight size={15} /></button>
        </div>
      </section>
      <footer className="page-footer"><span><span className="footer-dot" />所有 IoT 服务运行正常</span><span>数据更新于刚刚 · Roseboard IoT</span></footer>
    </>
  );
}

function SecurityRow({ icon: Icon, label, detail, warning = false }: { icon: typeof KeyRound; label: string; detail: string; warning?: boolean }) {
  return <div className="security-row"><span className={`security-row-icon${warning ? ' warning' : ''}`}><Icon size={15} /></span><span className="security-row-copy"><strong>{label}</strong><small>{detail}</small></span><span className={`row-indicator${warning ? ' warning' : ''}`} /></div>;
}

function ModuleSection({
  moduleKey,
  title,
  description,
}: {
  moduleKey: Exclude<NavKey, 'overview'>;
  title: string;
  description: string;
}) {
  const content = {
    users: {
      stats: [['设备总数', '1,284', '较上月 +8.2%'], ['在线设备', '892', '最近 30 天活跃'], ['待配置', '18', '需要完成初始化'], ['连接稳定性', '94.6%', '较上月 +2.1%']],
      rows: [['温控器 · TB-001', '生产区 / MQTT', '今天 09:42', '在线'], ['网关 · GW-024', '仓储区 / WebSocket', '昨天 18:20', '在线'], ['传感器 · SN-118', '测试区 / MQTT', '昨天 14:03', '待配置']],
    },
    tenants: {
      stats: [['项目空间', '24', '较上月 +3'], ['运行中', '21', '设备连接正常'], ['待配置', '3', '需要完成初始化'], ['本月遥测', '1.24M', '较上月 +8.4%']],
      rows: [['Roseboard Production', '生产项目', '99.98%', '运行中'], ['Northwind Demo', '演示项目', '100%', '运行中'], ['Acme Sandbox', '测试项目', '—', '待配置']],
    },
    security: {
      stats: [['告警评分', '86 / 100', '状态良好'], ['待处理告警', '03', '过去 30 天'], ['启用策略', '12', '覆盖核心设备'], ['异常连接', '07', '较上月 -24.5%']],
      rows: [['设备认证', '凭证策略', '全部项目', '已启用'], ['离线设备监控', '运行检测', '实时', '需复核'], ['告警通知', '通知策略', '全局', '已启用']],
    },
    audit: {
      stats: [['今日操作', '438', '较昨日 +12.5%'], ['遥测事件', '217', '采集成功率 99.1%'], ['设备变更', '17', '需要留痕'], ['告警事件', '06', '较上月 -18.2%']],
      rows: [['创建设备', '赵智', '刚刚', '已完成'], ['更新设备配置', '陈默', '12 分钟前', '已完成'], ['设备连接异常', '告警中心', '1 小时前', '需关注']],
    },
    settings: {
      stats: [['系统版本', '0.1.0', '当前稳定版'], ['集成数量', '12', '已连接服务'], ['告警规则', '09', '全部启用'], ['环境状态', '正常', '所有依赖在线']],
      rows: [['设备认证', 'JWT / API Key', '默认配置', '已启用'], ['数据采集', 'MQTT / WebSocket', '开发环境', '已连接'], ['日志保留期', '180 天', '平台策略', '已配置']],
    },
  }[moduleKey];

  return (
    <section className="module-section">
      <div className="module-intro"><div><h2>{title}概览</h2><p>{description}</p></div><button className="text-button" type="button">导出数据 <ArrowRight size={14} /></button></div>
      <div className="module-stats">{content.stats.map(([label, value, detail]) => <article className="module-stat" key={label}><span>{label}</span><strong>{value}</strong><small>{detail}</small></article>)}</div>
      <div className="module-table-card">
        <div className="module-table-heading"><div><h2>最近变更</h2><p>当前工作空间的最新数据</p></div><button className="secondary-button" type="button">筛选</button></div>
        <div className="module-table">
          <div className="module-table-row module-table-header"><span>项目</span><span>类型 / 发起人</span><span>更新时间</span><span>状态</span></div>
          {content.rows.map(([name, type, time, status]) => <div className="module-table-row" key={name}><strong>{name}</strong><span>{type}</span><span>{time}</span><span className={status === '需复核' || status === '待审核' || status === '待配置' ? 'module-status-warning' : 'module-status-good'}>{status}</span></div>)}
        </div>
      </div>
    </section>
  );
}

createRoot(document.getElementById('root')!).render(<App />);
