import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import { apiGet, apiPost, toErrorMessage, tokenStore } from './apiClient';

const AuthContext = createContext(null);

export function AuthProvider({ children }) {
  const [user, setUser] = useState(tokenStore.user);
  const [isAuthenticated, setIsAuthenticated] = useState(Boolean(tokenStore.access));
  const [restoring, setRestoring] = useState(() => !tokenStore.access && Boolean(tokenStore.refresh));

  useEffect(() => {
    if (!restoring) return;
    let active = true;
    apiGet('/users/me').then(profile => {
      if (!active) return;
      setUser(profile);
      setIsAuthenticated(Boolean(tokenStore.access));
    }).catch(() => {
      if (!active) return;
      tokenStore.clear();
      setUser(null);
      setIsAuthenticated(false);
    }).finally(() => { if (active) setRestoring(false); });
    return () => { active = false; };
  }, [restoring]);

  const logout = useCallback(() => {
    tokenStore.clear();
    sessionStorage.removeItem('bcm-booking-draft');
    setUser(null);
    setIsAuthenticated(false);
  }, []);

  // Đồng bộ khi token bị xoá từ nơi khác (vd: interceptor gặp 401 không refresh được).
  useEffect(() => {
    const onExpired = () => {
      setUser(null);
      setIsAuthenticated(false);
    };
    const onSessionChanged = () => {
      setUser(tokenStore.user);
      setIsAuthenticated(Boolean(tokenStore.access));
    };
    window.addEventListener('bcm:auth-expired', onExpired);
    window.addEventListener('bcm:session-changed', onSessionChanged);
    return () => {
      window.removeEventListener('bcm:auth-expired', onExpired);
      window.removeEventListener('bcm:session-changed', onSessionChanged);
    };
  }, []);

  const applySession = useCallback((payload, options) => {
    if (!payload?.accessToken || !payload?.refreshToken || !payload?.user) {
      throw new Error('Invalid authentication response');
    }
    tokenStore.save(payload, options);
    setUser(payload.user ?? null);
    setIsAuthenticated(true);
  }, []);

  const login = useCallback(
    async ({ email, password, remember = true }) => {
      try {
        const payload = await apiPost('/auth/login', { email, password });
        applySession(payload, { persist: remember });
        return { ok: true };
      } catch (error) {
        return { ok: false, message: toErrorMessage(error) };
      }
    },
    [applySession],
  );

  const register = useCallback(
    async ({ email, password, fullName, phone, address, remember = true }) => {
      try {
        const payload = await apiPost('/auth/register', {
          email,
          password,
          fullName,
          phone,
          ...(address ? { address } : {}),
        });
        applySession(payload, { persist: remember });
        return { ok: true };
      } catch (error) {
        return { ok: false, message: toErrorMessage(error) };
      }
    },
    [applySession],
  );

  const value = useMemo(
    () => ({ user, isAuthenticated, login, register, logout }),
    [user, isAuthenticated, login, register, logout],
  );

  return <AuthContext.Provider value={value}>{restoring ? <p role="status">Đang khôi phục phiên đăng nhập…</p> : children}</AuthContext.Provider>;
}

export function useAuth() {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth phải nằm trong <AuthProvider>');
  return ctx;
}
