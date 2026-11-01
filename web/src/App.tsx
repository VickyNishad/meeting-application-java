import { lazy, Suspense, useEffect, type ReactNode } from 'react';
import { BrowserRouter, Navigate, Route, Routes, useLocation, useNavigate } from 'react-router';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { Toaster } from 'sonner';
import { AuthProvider } from './lib/auth/AuthProvider';
import { FullPageSpinner, PublicOnly, RequireAdmin, RequireAuth, loginRedirectPath } from './lib/auth/guards';
import { ThemeProvider, useTheme } from './lib/theme';
import { api } from './lib/api/client';
import { ApiError } from './lib/api/problem';
import { AppShell, PublicShell } from './components/layout/AppShell';

// Route-level code splitting: every page is its own chunk; the LiveKit room is never in the entry chunk.
const LoginPage = lazy(() => import('./pages/auth/LoginPage'));
const RegisterPage = lazy(() => import('./pages/auth/RegisterPage'));
const OAuthCallbackPage = lazy(() => import('./pages/auth/OAuthCallbackPage'));
const VerifyEmailPage = lazy(() => import('./pages/auth/VerifyEmailPage'));
const ForgotPasswordPage = lazy(() => import('./pages/auth/ForgotPasswordPage'));
const ResetPasswordPage = lazy(() => import('./pages/auth/ResetPasswordPage'));
const DashboardPage = lazy(() => import('./pages/DashboardPage'));
const MeetingsListPage = lazy(() => import('./pages/meetings/MeetingsListPage'));
const MeetingFormPage = lazy(() => import('./pages/meetings/MeetingFormPage'));
const MeetingDetailsPage = lazy(() => import('./pages/meetings/MeetingDetailsPage'));
const JoinByCodePage = lazy(() => import('./pages/meetings/JoinByCodePage'));
const MeetingRoomPage = lazy(() => import('./pages/room/MeetingRoomPage'));
const RecordingsPage = lazy(() => import('./pages/recordings/RecordingsPage'));
const AvailabilityPage = lazy(() => import('./pages/scheduling/AvailabilityPage'));
const BookingPagesPage = lazy(() => import('./pages/scheduling/BookingPagesPage'));
const HostBookingsPage = lazy(() => import('./pages/scheduling/HostBookingsPage'));
const PublicBookingPage = lazy(() => import('./pages/public/PublicBookingPage'));
const BookingConfirmedPage = lazy(() => import('./pages/public/BookingConfirmedPage'));
const ManageBookingPage = lazy(() => import('./pages/public/ManageBookingPage'));
const UserSettingsPage = lazy(() => import('./pages/settings/UserSettingsPage'));
const OrganizationPage = lazy(() => import('./pages/settings/OrganizationPage'));
const AdminPage = lazy(() => import('./pages/admin/AdminPage'));
const NotFoundPage = lazy(() => import('./pages/NotFoundPage'));

function createQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: {
        staleTime: 15_000,
        refetchOnWindowFocus: false,
        retry: (failureCount, error) => {
          if (error instanceof ApiError && error.status >= 400 && error.status < 500) return false;
          return failureCount < 2;
        },
      },
      mutations: { retry: false },
    },
  });
}

const queryClient = createQueryClient();

/** Wires the API client's "refresh failed" hook to a router redirect. */
function AuthRedirector() {
  const navigate = useNavigate();
  const location = useLocation();
  useEffect(() => {
    api.setAuthFailureHandler(() => {
      if (location.pathname.startsWith('/login')) return;
      navigate(loginRedirectPath(location.pathname, location.search), { replace: true });
    });
    return () => api.setAuthFailureHandler(null);
  }, [navigate, location.pathname, location.search]);
  return null;
}

function ThemedToaster() {
  const { resolved } = useTheme();
  return <Toaster theme={resolved} richColors closeButton position="top-right" />;
}

function Page({ children }: { children: ReactNode }) {
  return <Suspense fallback={<FullPageSpinner />}>{children}</Suspense>;
}

export function AppRoutes() {
  return (
    <Routes>
      <Route element={<PublicShell />}>
        <Route path="/login" element={<Page><PublicOnly><LoginPage /></PublicOnly></Page>} />
        <Route path="/register" element={<Page><PublicOnly><RegisterPage /></PublicOnly></Page>} />
        <Route path="/oauth/callback" element={<Page><OAuthCallbackPage /></Page>} />
        <Route path="/verify-email" element={<Page><VerifyEmailPage /></Page>} />
        <Route path="/forgot-password" element={<Page><ForgotPasswordPage /></Page>} />
        <Route path="/reset-password" element={<Page><ResetPasswordPage /></Page>} />
        <Route path="/j/:joinCode" element={<Page><JoinByCodePage /></Page>} />
        <Route path="/book/:slug" element={<Page><PublicBookingPage /></Page>} />
        <Route path="/book/:slug/confirmed" element={<Page><BookingConfirmedPage /></Page>} />
        <Route path="/bookings/manage/:token" element={<Page><ManageBookingPage /></Page>} />
      </Route>

      <Route path="/meetings/:meetingId/room" element={<Page><MeetingRoomPage /></Page>} />

      <Route
        element={
          <RequireAuth>
            <AppShell />
          </RequireAuth>
        }
      >
        <Route path="/dashboard" element={<Page><DashboardPage /></Page>} />
        <Route path="/meetings" element={<Page><MeetingsListPage /></Page>} />
        <Route path="/meetings/new" element={<Page><MeetingFormPage /></Page>} />
        <Route path="/meetings/:meetingId" element={<Page><MeetingDetailsPage /></Page>} />
        <Route path="/meetings/:meetingId/edit" element={<Page><MeetingFormPage /></Page>} />
        <Route path="/recordings" element={<Page><RecordingsPage /></Page>} />
        <Route path="/scheduling/availability" element={<Page><AvailabilityPage /></Page>} />
        <Route path="/scheduling/pages" element={<Page><BookingPagesPage /></Page>} />
        <Route path="/scheduling/bookings" element={<Page><HostBookingsPage /></Page>} />
        <Route path="/settings" element={<Page><UserSettingsPage /></Page>} />
        <Route path="/organizations" element={<Page><OrganizationPage /></Page>} />
        <Route path="/admin" element={<Page><RequireAdmin><AdminPage /></RequireAdmin></Page>} />
      </Route>

      <Route path="/" element={<Navigate to="/dashboard" replace />} />
      <Route path="*" element={<PublicShell><Page><NotFoundPage /></Page></PublicShell>} />
    </Routes>
  );
}

export function App() {
  return (
    <ThemeProvider>
      <QueryClientProvider client={queryClient}>
        <BrowserRouter>
          <AuthProvider>
            <AuthRedirector />
            <AppRoutes />
            <ThemedToaster />
          </AuthProvider>
        </BrowserRouter>
      </QueryClientProvider>
    </ThemeProvider>
  );
}
