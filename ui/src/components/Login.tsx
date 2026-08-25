import { useState } from 'react';
import type { FormEvent } from 'react';
import { ArrowRight, Eye, EyeOff, LockKeyhole, ShieldCheck } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Card } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { login } from '../auth';
import { BrandMark } from './Sidebar';

type LoginProps = {
  onSuccess: (username: string) => void;
};

export function Login({ onSuccess }: LoginProps) {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState('');

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!username.trim() || !password) {
      setError('请输入用户名和密码。');
      return;
    }

    setSubmitting(true);
    setError('');
    try {
      await login({ username: username.trim(), password });
      onSuccess(username.trim());
    } catch (loginError) {
      setError(loginError instanceof Error ? loginError.message : '登录失败，请稍后重试。');
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <main className="login-shell">
      <div className="login-decoration login-decoration-one" />
      <div className="login-decoration login-decoration-two" />
      <section className="login-layout">
        <div className="login-intro">
          <div className="login-brand"><BrandMark /><span>Roseboard</span></div>
          <div className="login-intro-copy">
            <span className="login-eyebrow"><ShieldCheck size={15} /> IoT Operations Platform</span>
            <h1>让设备连接更简单，<br /><span>让数据驱动运营。</span></h1>
            <p>统一管理设备、遥测数据和告警策略，让物联网运行状态清晰可控。</p>
          </div>
          <div className="login-trust"><span className="login-trust-dot" />平台运行正常 <span>·</span> 设备数据已加密</div>
        </div>

        <div className="login-card-wrap">
          <Card className="login-card">
            <div className="login-card-heading">
              <span className="login-card-icon"><LockKeyhole size={18} /></span>
              <h2>登录 Roseboard IoT</h2>
              <p>使用你的物联网平台账户继续</p>
            </div>
            <form className="login-form" onSubmit={handleSubmit} noValidate>
              <label className="login-field">
                <span>用户名或邮箱</span>
                <Input
                  className="login-control"
                  autoComplete="username"
                  autoFocus
                  value={username}
                  onChange={(event) => setUsername(event.target.value)}
                  placeholder="name@example.com"
                  disabled={submitting}
                />
              </label>
              <label className="login-field">
                <span>密码</span>
                <div className="password-input-wrap">
                  <Input
                    className="login-control"
                    type={showPassword ? 'text' : 'password'}
                    autoComplete="current-password"
                    value={password}
                    onChange={(event) => setPassword(event.target.value)}
                    placeholder="请输入密码"
                    disabled={submitting}
                  />
                  <Button variant="ghost" size="icon-sm" className="password-toggle" type="button" aria-label={showPassword ? '隐藏密码' : '显示密码'} onClick={() => setShowPassword((visible) => !visible)}>
                    {showPassword ? <EyeOff /> : <Eye />}
                  </Button>
                </div>
              </label>
              {error && <p className="login-error" role="alert">{error}</p>}
              <Button className="login-submit" type="submit" disabled={submitting}>
                <span>{submitting ? '登录中…' : '登录'}</span>
                {!submitting && <ArrowRight data-icon="inline-end" />}
              </Button>
            </form>
            <div className="login-card-footer"><span>需要帮助？</span><a href="mailto:support@roseboard.local">联系管理员</a></div>
          </Card>
          <p className="login-legal">登录即表示你同意 Roseboard 的服务条款和隐私政策。</p>
        </div>
      </section>
    </main>
  );
}
