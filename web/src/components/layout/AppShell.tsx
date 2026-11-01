import { useState, type ReactNode } from 'react';
import { Link, NavLink, Outlet, useNavigate } from 'react-router';
import {
  Building2,
  CalendarCheck,
  CalendarClock,
  CalendarDays,
  LayoutDashboard,
  LogOut,
  Menu,
  Settings,
  ShieldCheck,
  Video,
  Film,
  X,
} from 'lucide-react';
import { Button } from '../ui/button';
import { Avatar } from '../ui/primitives';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '../ui/overlays';
import { useAuth } from '@/lib/auth/AuthProvider';
import { ThemeToggle } from './ThemeToggle';
import { NotificationsMenu } from './NotificationsMenu';
import { cn } from '@/lib/utils';

export function Logo({ className }: { className?: string }) {
  return (
    <Link to="/" className={cn('flex items-center gap-2 font-semibold tracking-tight', className)}>
      <span className="flex size-8 items-center justify-center rounded-lg bg-primary text-primary-foreground">
        <Video className="size-4" aria-hidden="true" />
      </span>
      <span>Meetly</span>
    </Link>
  );
}

interface NavItem {
  to: string;
  label: string;
  icon: typeof Video;
}

const mainNav: NavItem[] = [
  { to: '/dashboard', label: 'Dashboard', icon: LayoutDashboard },
  { to: '/meetings', label: 'Meetings', icon: Video },
  { to: '/recordings', label: 'Recordings', icon: Film },
];
const schedulingNav: NavItem[] = [
  { to: '/scheduling/bookings', label: 'Bookings', icon: CalendarCheck },
  { to: '/scheduling/pages', label: 'Booking pages', icon: CalendarDays },
  { to: '/scheduling/availability', label: 'Availability', icon: CalendarClock },
];
const accountNav: NavItem[] = [
  { to: '/settings', label: 'Settings', icon: Settings },
  { to: '/organizations', label: 'Organization', icon: Building2 },
];

function NavSection({ title, items, onNavigate }: { title?: string; items: NavItem[]; onNavigate?: () => void }) {
  return (
    <div className="grid gap-0.5">
      {title && <p className="px-3 pb-1 pt-4 text-xs font-medium uppercase tracking-wider text-muted-foreground">{title}</p>}
      {items.map((item) => (
        <NavLink
          key={item.to}
          to={item.to}
          onClick={onNavigate}
          className={({ isActive }) =>
            cn(
              'flex items-center gap-3 rounded-md px-3 py-2 text-sm font-medium transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring',
              isActive ? 'bg-primary/10 text-primary' : 'text-muted-foreground hover:bg-accent hover:text-foreground',
            )
          }
        >
          <item.icon className="size-4" aria-hidden="true" />
          {item.label}
        </NavLink>
      ))}
    </div>
  );
}

function SidebarContent({ onNavigate }: { onNavigate?: () => void }) {
  const { user } = useAuth();
  return (
    <nav aria-label="Main" className="flex flex-col gap-1 p-3">
      <NavSection items={mainNav} onNavigate={onNavigate} />
      <NavSection title="Scheduling" items={schedulingNav} onNavigate={onNavigate} />
      <NavSection title="Account" items={accountNav} onNavigate={onNavigate} />
      {user?.systemRole === 'SYSTEM_ADMIN' && (
        <NavSection title="Admin" items={[{ to: '/admin', label: 'Administration', icon: ShieldCheck }]} onNavigate={onNavigate} />
      )}
    </nav>
  );
}

function UserMenu() {
  const { user, signOut } = useAuth();
  const navigate = useNavigate();
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <button
          className="rounded-full focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
          aria-label="Account menu"
        >
          <Avatar name={user?.displayName} src={user?.avatarUrl} />
        </button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end">
        <DropdownMenuLabel className="font-normal">
          <div className="text-sm font-medium text-foreground">{user?.displayName}</div>
          <div className="truncate text-xs">{user?.email}</div>
        </DropdownMenuLabel>
        <DropdownMenuSeparator />
        <DropdownMenuItem onSelect={() => navigate('/settings')}>
          <Settings aria-hidden="true" /> Settings
        </DropdownMenuItem>
        <DropdownMenuItem
          destructive
          onSelect={() => {
            void signOut().then(() => navigate('/login'));
          }}
        >
          <LogOut aria-hidden="true" /> Sign out
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}

export function AppShell() {
  const [mobileOpen, setMobileOpen] = useState(false);
  return (
    <div className="min-h-dvh bg-background">
      <a
        href="#main"
        className="sr-only focus:not-sr-only focus:fixed focus:left-2 focus:top-2 focus:z-50 focus:rounded-md focus:bg-primary focus:px-3 focus:py-2 focus:text-primary-foreground"
      >
        Skip to content
      </a>
      <aside className="fixed inset-y-0 left-0 z-30 hidden w-60 border-r bg-card lg:block">
        <div className="flex h-14 items-center border-b px-4">
          <Logo />
        </div>
        <SidebarContent />
      </aside>

      {mobileOpen && (
        <div className="fixed inset-0 z-40 lg:hidden" role="dialog" aria-modal="true" aria-label="Navigation">
          <div className="absolute inset-0 bg-black/50" onClick={() => setMobileOpen(false)} aria-hidden="true" />
          <div className="absolute inset-y-0 left-0 w-64 overflow-y-auto border-r bg-card shadow-xl">
            <div className="flex h-14 items-center justify-between border-b px-4">
              <Logo />
              <Button variant="ghost" size="icon" aria-label="Close navigation" onClick={() => setMobileOpen(false)}>
                <X aria-hidden="true" />
              </Button>
            </div>
            <SidebarContent onNavigate={() => setMobileOpen(false)} />
          </div>
        </div>
      )}

      <div className="lg:pl-60">
        <header className="sticky top-0 z-20 flex h-14 items-center gap-2 border-b bg-background/85 px-4 backdrop-blur">
          <Button
            variant="ghost"
            size="icon"
            className="lg:hidden"
            aria-label="Open navigation"
            onClick={() => setMobileOpen(true)}
          >
            <Menu aria-hidden="true" />
          </Button>
          <Logo className="lg:hidden" />
          <div className="ml-auto flex items-center gap-1">
            <NotificationsMenu />
            <ThemeToggle />
            <UserMenu />
          </div>
        </header>
        <main id="main" className="mx-auto w-full max-w-6xl px-4 py-6 sm:px-6 lg:py-8">
          <Outlet />
        </main>
      </div>
    </div>
  );
}

/** Minimal chrome for public / auth pages. */
export function PublicShell({ children }: { children?: ReactNode }) {
  return (
    <div className="flex min-h-dvh flex-col bg-gradient-to-b from-primary/5 via-background to-background">
      <header className="flex h-14 items-center justify-between px-4 sm:px-6">
        <Logo />
        <ThemeToggle />
      </header>
      <main id="main" className="flex flex-1 flex-col px-4 pb-10 sm:px-6">
        {children ?? <Outlet />}
      </main>
    </div>
  );
}
