import type { ReactNode } from 'react';
import { ChevronDown, Globe2, Moon, Sparkles, Sun, UserRound } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { BrandMark } from './Sidebar';

type HeaderProps = {
  username: string;
  workspaceOpen: boolean;
  profileOpen: boolean;
  darkMode: boolean;
  onWorkspaceToggle: () => void;
  onProfileToggle: () => void;
  onThemeToggle: () => void;
  onOpenSettings: () => void;
  onLogout: () => void;
};

export function Header({
  username,
  workspaceOpen,
  profileOpen,
  darkMode,
  onWorkspaceToggle,
  onProfileToggle,
  onThemeToggle,
  onOpenSettings,
  onLogout,
}: HeaderProps) {
  return (
    <header className="topbar">
      <div className="topbar-left">
        <a className="brand" href="/" aria-label="Roseboard 首页">
          <BrandMark />
          <span>Roseboard</span>
        </a>
        <div className="workspace-wrap">
          <Button variant="ghost" className="workspace-trigger" type="button" aria-expanded={workspaceOpen} onClick={onWorkspaceToggle}>
            <span className="workspace-avatar">R</span>
            <span className="workspace-name">Roseboard IoT Cloud</span>
            <ChevronDown size={15} />
          </Button>
          {workspaceOpen && (
            <div className="popover workspace-menu">
              <p className="popover-label">项目空间</p>
              <Button variant="ghost" className="workspace-option workspace-option-active" type="button" onClick={onWorkspaceToggle}>
                <span><strong>Roseboard IoT Cloud</strong><small>当前项目空间</small></span>
                <span className="status-dot" />
              </Button>
              <Button variant="ghost" className="workspace-option" type="button" onClick={onWorkspaceToggle}>
                <span className="workspace-avatar workspace-avatar-muted">D</span>
                <span><strong>Demo IoT Project</strong><small>演示项目</small></span>
              </Button>
            </div>
          )}
        </div>
      </div>

      <nav className="topbar-nav" aria-label="产品导航">
        <a className="topbar-link topbar-link-active" href="#overview">设备控制台</a>
        <a className="topbar-link" href="#security">告警中心</a>
        <a className="topbar-link" href="#docs">开发文档</a>
      </nav>

      <div className="topbar-actions">
        <Button variant="ghost" size="icon-sm" className="icon-button" type="button" aria-label="切换语言" title="切换语言">
          <Globe2 size={17} />
          <span className="language-code">CN</span>
        </Button>
        <Button variant="ghost" size="icon-sm" className="icon-button" type="button" aria-label="切换主题" title="切换主题" onClick={onThemeToggle}>
          {darkMode ? <Sun size={17} /> : <Moon size={17} />}
        </Button>
        <div className="profile-wrap">
          <Button variant="ghost" className="profile-trigger" type="button" aria-expanded={profileOpen} onClick={onProfileToggle}>
            <span className="profile-avatar">{username.slice(0, 1).toUpperCase()}</span>
            <ChevronDown size={14} />
          </Button>
          {profileOpen && (
            <div className="popover profile-menu">
              <div className="profile-heading">
                <span className="profile-avatar profile-avatar-large">{username.slice(0, 1).toUpperCase()}</span>
                <span><strong>{username}</strong><small>已登录的 Roseboard IoT 账户</small></span>
              </div>
              <div className="popover-divider" />
              <Button variant="ghost" className="menu-action" type="button" onClick={onOpenSettings}><UserRound size={15} />个人设置</Button>
              <Button variant="ghost" className="menu-action" type="button" onClick={onLogout}>退出登录</Button>
            </div>
          )}
        </div>
      </div>
    </header>
  );
}

export function SectionIcon({ children }: { children: ReactNode }) {
  return <span className="section-icon">{children}</span>;
}

export function SparkleMark() {
  return <Sparkles size={16} strokeWidth={1.9} />;
}
