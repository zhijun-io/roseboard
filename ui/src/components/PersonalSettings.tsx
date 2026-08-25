import { useEffect, useMemo, useState } from 'react';
import type { FormEvent } from 'react';
import { KeyRound, LogOut, MonitorCog, ShieldCheck, UserRound } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Switch } from '@/components/ui/switch';
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { changePassword, getCurrentUser, getMfaSettings, type CurrentUser } from '../auth';

type SettingsSection = 'profile' | 'security' | 'preferences';

type PersonalSettingsProps = {
  username: string;
  darkMode: boolean;
  onThemeToggle: () => void;
  onLogout: () => void;
};

type Preferences = {
  emailNotifications: boolean;
  securityAlerts: boolean;
  productUpdates: boolean;
};

const defaultPreferences: Preferences = {
  emailNotifications: true,
  securityAlerts: true,
  productUpdates: false,
};

export function PersonalSettings({ username, darkMode, onThemeToggle, onLogout }: PersonalSettingsProps) {
  const [section, setSection] = useState<SettingsSection>('profile');
  const [user, setUser] = useState<CurrentUser | null>(null);
  const [profileError, setProfileError] = useState('');
  const [mfaEnabled, setMfaEnabled] = useState<boolean | null>(null);
  const [preferences, setPreferences] = useState<Preferences>(() => loadPreferences());

  useEffect(() => {
    let active = true;
    Promise.all([getCurrentUser(), getMfaSettings()])
      .then(([currentUser, mfaSettings]) => {
        if (!active) return;
        setUser(currentUser);
        setMfaEnabled(Object.keys(mfaSettings.configs ?? {}).length > 0);
      })
      .catch((error: unknown) => {
        if (active) setProfileError(error instanceof Error ? error.message : '无法读取账户信息。');
      });
    return () => { active = false; };
  }, []);

  const displayName = useMemo(() => {
    const fullName = [user?.firstName, user?.lastName].filter(Boolean).join(' ');
    return fullName || username;
  }, [user, username]);

  function updatePreference(key: keyof Preferences) {
    setPreferences((current) => {
      const next = { ...current, [key]: !current[key] };
      localStorage.setItem('roseboard.preferences', JSON.stringify(next));
      return next;
    });
  }

  return (
    <section className="personal-settings">
      <div className="settings-navigation">
        <div className="settings-navigation-title"><span className="settings-avatar">{displayName.slice(0, 1).toUpperCase()}</span><span><strong>{displayName}</strong><small>{user?.email ?? username}</small></span></div>
        <Tabs className="settings-tabs" orientation="vertical" value={section} onValueChange={(value) => setSection(value as SettingsSection)}>
          <TabsList variant="line" className="settings-tab-list">
            <TabsTrigger value="profile" className="settings-nav-item"><UserRound />个人资料</TabsTrigger>
            <TabsTrigger value="security" className="settings-nav-item"><ShieldCheck />安全与登录</TabsTrigger>
            <TabsTrigger value="preferences" className="settings-nav-item"><MonitorCog />偏好设置</TabsTrigger>
          </TabsList>
        </Tabs>
        <div className="settings-navigation-divider" />
        <button className="settings-nav-item settings-nav-danger" type="button" onClick={onLogout}><LogOut size={16} />退出登录</button>
      </div>
      <div className="settings-content">
        {section === 'profile' && <ProfilePanel user={user} username={username} error={profileError} />}
        {section === 'security' && <SecurityPanel mfaEnabled={mfaEnabled} onLogout={onLogout} />}
        {section === 'preferences' && <PreferencesPanel darkMode={darkMode} preferences={preferences} onThemeToggle={onThemeToggle} onToggle={updatePreference} />}
      </div>
    </section>
  );
}

function ProfilePanel({ user, username, error }: { user: CurrentUser | null; username: string; error: string }) {
  return (
    <div className="settings-panel-stack">
      <SettingsPanelHeader title="个人资料" description="查看你在 Roseboard 工作空间中的身份信息。" />
      {error && <div className="settings-error" role="alert">{error}</div>}
      <div className="settings-card">
        <div className="settings-card-heading"><div><h3>基本信息</h3><p>资料由工作空间管理员维护。</p></div><span className="read-only-badge">只读</span></div>
        <div className="settings-form-grid">
          <ReadOnlyField label="用户名" value={username} />
          <ReadOnlyField label="邮箱" value={user?.email ?? '加载中…'} />
          <ReadOnlyField label="姓" value={user?.firstName ?? '—'} />
          <ReadOnlyField label="名" value={user?.lastName ?? '—'} />
          <ReadOnlyField label="手机号" value={user?.phone ?? '未设置'} />
          <ReadOnlyField label="角色" value={user?.authority ?? '加载中…'} />
        </div>
        <p className="settings-card-note">如需修改姓名、邮箱或手机号，请联系工作空间管理员。</p>
      </div>
    </div>
  );
}

function SecurityPanel({ mfaEnabled, onLogout }: { mfaEnabled: boolean | null; onLogout: () => void }) {
  const [currentPassword, setCurrentPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [status, setStatus] = useState<{ type: 'error' | 'success'; text: string } | null>(null);
  const [submitting, setSubmitting] = useState(false);

  async function submitPassword(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (newPassword.length < 8) {
      setStatus({ type: 'error', text: '新密码至少需要 8 个字符。' });
      return;
    }
    if (newPassword !== confirmPassword) {
      setStatus({ type: 'error', text: '两次输入的新密码不一致。' });
      return;
    }
    setSubmitting(true);
    setStatus(null);
    try {
      await changePassword(currentPassword, newPassword);
      setCurrentPassword('');
      setNewPassword('');
      setConfirmPassword('');
      setStatus({ type: 'success', text: '密码已更新，其他登录会话已失效。' });
    } catch (error) {
      setStatus({ type: 'error', text: error instanceof Error ? error.message : '密码更新失败。' });
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="settings-panel-stack">
      <div className="settings-card">
        <div className="settings-card-heading"><div><h3>修改密码</h3><p>修改密码后，其他设备上的会话将被撤销。</p></div><KeyRound size={18} className="settings-heading-icon" /></div>
        <form className="settings-password-form" onSubmit={submitPassword}>
          <SettingsInput label="当前密码" value={currentPassword} onChange={setCurrentPassword} autoComplete="current-password" />
          <SettingsInput label="新密码" value={newPassword} onChange={setNewPassword} autoComplete="new-password" />
          <SettingsInput label="确认新密码" value={confirmPassword} onChange={setConfirmPassword} autoComplete="new-password" />
          {status && <div className={`settings-message settings-message-${status.type}`} role="alert">{status.text}</div>}
          <Button className="settings-primary-button" type="submit" disabled={submitting}>{submitting ? '保存中…' : '更新密码'}</Button>
        </form>
      </div>
      <div className="settings-card security-setting-row"><div className="security-setting-icon"><ShieldCheck size={18} /></div><div><h3>多因素认证</h3><p>{mfaEnabled === null ? '正在读取安全状态…' : mfaEnabled ? '已配置至少一种多因素认证方式。' : '尚未配置多因素认证，建议尽快启用。'}</p></div><span className={`security-setting-status${mfaEnabled ? ' enabled' : ''}`}>{mfaEnabled === null ? '检查中' : mfaEnabled ? '已启用' : '未启用'}</span></div>
      <div className="settings-card danger-card"><div><h3>退出所有会话</h3><p>退出当前账户并清除本设备上的登录凭证。</p></div><Button className="settings-danger-button" variant="destructive" type="button" onClick={onLogout}>退出登录</Button></div>
    </div>
  );
}

function PreferencesPanel({ darkMode, preferences, onThemeToggle, onToggle }: { darkMode: boolean; preferences: Preferences; onThemeToggle: () => void; onToggle: (key: keyof Preferences) => void }) {
  return (
    <div className="settings-panel-stack">
      <SettingsPanelHeader title="偏好设置" description="调整当前浏览器中的显示和通知偏好。" />
      <div className="settings-card"><div className="settings-card-heading"><div><h3>外观</h3><p>这些设置仅保存在当前浏览器。</p></div></div><PreferenceRow label="深色模式" description="降低夜间使用时的视觉亮度。" enabled={darkMode} onToggle={onThemeToggle} /><div className="settings-language-row"><div><strong>界面语言</strong><small>选择控制台显示语言。</small></div><select defaultValue="zh-CN" aria-label="界面语言"><option value="zh-CN">简体中文</option><option value="en-US">English</option></select></div></div>
      <div className="settings-card"><div className="settings-card-heading"><div><h3>通知</h3><p>选择你希望接收的工作空间通知。</p></div></div><PreferenceRow label="安全提醒" description="异常登录和策略风险提醒。" enabled={preferences.securityAlerts} onToggle={() => onToggle('securityAlerts')} /><PreferenceRow label="邮件通知" description="接收重要的账户和工作空间通知。" enabled={preferences.emailNotifications} onToggle={() => onToggle('emailNotifications')} /><PreferenceRow label="产品更新" description="接收 Roseboard 功能和版本更新。" enabled={preferences.productUpdates} onToggle={() => onToggle('productUpdates')} /></div>
    </div>
  );
}

function ReadOnlyField({ label, value }: { label: string; value: string }) {
  return <label className="settings-field"><span>{label}</span><Input className="settings-input" value={value} readOnly /></label>;
}

function SettingsInput({ label, value, onChange, autoComplete }: { label: string; value: string; onChange: (value: string) => void; autoComplete: string }) {
  return <label className="settings-field"><span>{label}</span><Input className="settings-input" type="password" value={value} onChange={(event) => onChange(event.target.value)} autoComplete={autoComplete} /></label>;
}

function PreferenceRow({ label, description, enabled, onToggle }: { label: string; description: string; enabled: boolean; onToggle: () => void }) {
  return <div className="preference-row"><div><strong>{label}</strong><small>{description}</small></div><Switch checked={enabled} onCheckedChange={onToggle} aria-label={label} /></div>;
}
function SettingsPanelHeader({ title, description }: { title: string; description: string }) {
  return <div className="settings-panel-header"><h2>{title}</h2><p>{description}</p></div>;
}

function loadPreferences(): Preferences {
  try {
    const stored = JSON.parse(localStorage.getItem('roseboard.preferences') ?? 'null') as Partial<Preferences> | null;
    return { ...defaultPreferences, ...stored };
  } catch {
    return defaultPreferences;
  }
}
