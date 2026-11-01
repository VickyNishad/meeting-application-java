import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router';
import { RequireAdmin, RequireAuth, safeNext } from './guards';
import { StaticAuthProvider, type AuthContextValue, type AuthStatus } from './AuthProvider';
import type { UserProfile } from '../api/types';

const baseUser: UserProfile = {
  id: 'u1',
  displayName: 'Ada Lovelace',
  email: 'ada@example.com',
  timeZone: 'UTC',
  emailVerified: true,
  systemRole: 'USER',
  status: 'ACTIVE',
  createdAt: '2026-01-01T00:00:00Z',
};

function ctx(status: AuthStatus, user: UserProfile | null = null): AuthContextValue {
  return { status, user, signIn: () => undefined, signOut: async () => undefined, setUser: () => undefined };
}

function LoginProbe() {
  const loc = useLocation();
  return <p>login page {loc.search}</p>;
}

function renderAt(path: string, value: AuthContextValue, guard: 'auth' | 'admin' = 'auth') {
  const Guard = guard === 'auth' ? RequireAuth : RequireAdmin;
  return render(
    <StaticAuthProvider value={value}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/login" element={<LoginProbe />} />
          <Route
            path="*"
            element={
              <Guard>
                <p>secret content</p>
              </Guard>
            }
          />
        </Routes>
      </MemoryRouter>
    </StaticAuthProvider>,
  );
}

describe('RequireAuth', () => {
  it('shows a spinner while the session is being restored', () => {
    renderAt('/meetings', ctx('loading'));
    expect(screen.getByRole('status')).toBeInTheDocument();
    expect(screen.queryByText('secret content')).not.toBeInTheDocument();
  });

  it('redirects anonymous users to /login with a next parameter', () => {
    renderAt('/meetings/abc?tab=past', ctx('anonymous'));
    expect(screen.getByText(/login page/)).toHaveTextContent('?next=%2Fmeetings%2Fabc%3Ftab%3Dpast');
    expect(screen.queryByText('secret content')).not.toBeInTheDocument();
  });

  it('renders children for authenticated users', () => {
    renderAt('/meetings', ctx('authenticated', baseUser));
    expect(screen.getByText('secret content')).toBeInTheDocument();
  });
});

describe('RequireAdmin', () => {
  it('denies non-admin users', () => {
    renderAt('/admin', ctx('authenticated', baseUser), 'admin');
    expect(screen.getByRole('heading', { name: /access denied/i })).toBeInTheDocument();
    expect(screen.queryByText('secret content')).not.toBeInTheDocument();
  });

  it('allows SYSTEM_ADMIN', () => {
    renderAt('/admin', ctx('authenticated', { ...baseUser, systemRole: 'SYSTEM_ADMIN' }), 'admin');
    expect(screen.getByText('secret content')).toBeInTheDocument();
  });

  it('redirects anonymous users to login', () => {
    renderAt('/admin', ctx('anonymous'), 'admin');
    expect(screen.getByText(/login page/)).toBeInTheDocument();
  });
});

describe('safeNext', () => {
  it('only allows same-origin relative paths', () => {
    expect(safeNext('/meetings/1')).toBe('/meetings/1');
    expect(safeNext('//evil.com')).toBe('/dashboard');
    expect(safeNext('https://evil.com')).toBe('/dashboard');
    expect(safeNext(null)).toBe('/dashboard');
  });

  it('keeps query and hash of a legitimate path', () => {
    expect(safeNext('/meetings/1/room?x=1#chat')).toBe('/meetings/1/room?x=1#chat');
    expect(safeNext('/j/abc%2Fdef')).toBe('/j/abc%2Fdef');
  });

  it.each([
    '/\\evil.com',
    '/\\/evil.com',
    '\\\\evil.com',
    decodeURIComponent('/%5Cevil.com'),
    decodeURIComponent('/%5C%5Cevil.com'),
    '//evil.com/path',
    '///evil.com',
    '/\t/evil.com',
    '/\n/evil.com',
    '/\r\n/evil.com',
    '/\u0000/evil.com',
    'javascript:alert(1)',
    ' /meetings',
    'evil.com',
    '',
    undefined,
  ])('rejects %j', (next) => {
    expect(safeNext(next)).toBe('/dashboard');
  });

  it('encoded backslashes that stay encoded remain a harmless same-origin path', () => {
    expect(safeNext('/%5Cevil.com')).toBe('/%5Cevil.com');
  });
});
