const ACCESS_TOKEN_KEY = 'roseboard.accessToken';
const REFRESH_TOKEN_KEY = 'roseboard.refreshToken';
const USERNAME_KEY = 'roseboard.username';

export type LoginCredentials = {
  username: string;
  password: string;
};

export type LoginResult = {
  token: string;
  refreshToken: string;
};

export type CurrentUser = {
  id?: string;
  email?: string;
  firstName?: string;
  lastName?: string;
  phone?: string;
  authority?: string;
  tenantId?: string;
};

export function getAccessToken() {
  return localStorage.getItem(ACCESS_TOKEN_KEY);
}

export function getStoredUsername() {
  return localStorage.getItem(USERNAME_KEY);
}

export function hasSession() {
  return Boolean(getAccessToken());
}

export function getAuthHeaders(): HeadersInit {
  const token = getAccessToken();
  return token ? { Authorization: `Bearer ${token}` } : {};
}

function storeSession(result: LoginResult) {
  localStorage.setItem(ACCESS_TOKEN_KEY, result.token);
  localStorage.setItem(REFRESH_TOKEN_KEY, result.refreshToken);
}

async function parseResponse<T>(response: Response): Promise<T> {
  const payload = (await response.json().catch(() => null)) as (T & { message?: string; detail?: string }) | null;
  if (!response.ok) {
    const message = payload && typeof payload.message === 'string'
      ? payload.message
      : payload && typeof payload.detail === 'string'
        ? payload.detail
        : '请求失败，请稍后重试。';
    throw new Error(message);
  }
  if (!payload) {
    throw new Error('服务器返回了空响应。');
  }
  return payload as T;
}

export async function login(credentials: LoginCredentials): Promise<LoginResult> {
  const response = await fetch('/api/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(credentials),
  });
  const payload = await parseResponse<Partial<LoginResult>>(response);
  if (typeof payload.token !== 'string' || typeof payload.refreshToken !== 'string') {
    throw new Error('登录响应无效，请联系管理员。');
  }
  const result = { token: payload.token, refreshToken: payload.refreshToken };
  storeSession(result);
  localStorage.setItem(USERNAME_KEY, credentials.username);
  return result;
}

export async function getCurrentUser() {
  const response = await fetch('/api/users/me', { headers: getAuthHeaders() });
  return parseResponse<CurrentUser>(response);
}

export async function changePassword(currentPassword: string, newPassword: string) {
  const response = await fetch('/api/auth/changePassword', {
    method: 'POST',
    headers: { ...getAuthHeaders(), 'Content-Type': 'application/json' },
    body: JSON.stringify({ currentPassword, newPassword }),
  });
  const payload = await parseResponse<Partial<LoginResult>>(response);
  if (typeof payload.token !== 'string' || typeof payload.refreshToken !== 'string') {
    throw new Error('密码已更新，但登录凭证刷新失败，请重新登录。');
  }
  const result = { token: payload.token, refreshToken: payload.refreshToken };
  storeSession(result);
  return result;
}

export async function getMfaSettings() {
  const response = await fetch('/api/2fa/account/settings', { headers: getAuthHeaders() });
  return parseResponse<Record<string, unknown>>(response);
}

export function logout() {
  const refreshToken = localStorage.getItem(REFRESH_TOKEN_KEY);
  const headers = { ...getAuthHeaders(), 'Content-Type': 'application/json' };
  if (refreshToken) {
    void fetch('/api/logout', {
      method: 'POST',
      headers,
      body: JSON.stringify({ refreshToken }),
    });
  }
  localStorage.removeItem(ACCESS_TOKEN_KEY);
  localStorage.removeItem(REFRESH_TOKEN_KEY);
  localStorage.removeItem(USERNAME_KEY);
}
