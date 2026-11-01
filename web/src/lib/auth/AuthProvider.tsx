import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { api, type ApiClient } from '../api/client';
import { authApi } from '../api/endpoints';
import type { AuthResponse, UserProfile } from '../api/types';

export type AuthStatus = 'loading' | 'authenticated' | 'anonymous';

export interface AuthContextValue {
  status: AuthStatus;
  user: UserProfile | null;
  /** Store a session obtained from /auth/login. */
  signIn: (auth: AuthResponse) => void;
  /** Revoke the current session server-side (best effort) and clear local state. */
  signOut: () => Promise<void>;
  /** Replace the cached profile (e.g. after PATCH /users/me). */
  setUser: (user: UserProfile) => void;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({
  children,
  client = api,
  skipBootstrap = false,
}: {
  children: ReactNode;
  client?: ApiClient;
  /** Tests: do not call /auth/refresh on mount. */
  skipBootstrap?: boolean;
}) {
  const [status, setStatus] = useState<AuthStatus>(skipBootstrap ? 'anonymous' : 'loading');
  const [user, setUserState] = useState<UserProfile | null>(null);
  const queryClient = useQueryClient();

  useEffect(() => {
    return client.onAuthChange((auth) => {
      if (auth) {
        setUserState(auth.user);
        setStatus('authenticated');
      } else {
        setUserState(null);
        setStatus('anonymous');
      }
    });
  }, [client]);

  useEffect(() => {
    if (skipBootstrap) return;
    let cancelled = false;
    // Restore the session from the HttpOnly refresh cookie.
    void client.refresh().then((auth) => {
      if (cancelled) return;
      if (!auth) {
        setStatus('anonymous');
      }
    });
    return () => {
      cancelled = true;
    };
  }, [client, skipBootstrap]);

  const signIn = useCallback((auth: AuthResponse) => client.setSession(auth), [client]);

  const signOut = useCallback(async () => {
    try {
      await authApi.logout();
    } catch {
      // Session may already be gone; local state is still cleared.
    }
    client.setSession(null);
    queryClient.clear();
  }, [client, queryClient]);

  const setUser = useCallback((u: UserProfile) => setUserState(u), []);

  const value = useMemo<AuthContextValue>(
    () => ({ status, user, signIn, signOut, setUser }),
    [status, user, signIn, signOut, setUser],
  );
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used inside <AuthProvider>');
  return ctx;
}

/** Test helper: a static auth context value. */
export function StaticAuthProvider({ value, children }: { value: AuthContextValue; children: ReactNode }) {
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}
