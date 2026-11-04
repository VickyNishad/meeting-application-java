import { useEffect, useRef, type ReactNode } from 'react';
import { ChevronDown, X } from 'lucide-react';
import { Button } from '@/components/ui/button';

/**
 * Side panel frame: a header with the panel's icon, title and count, and a close control (a floating card on larger
 * screens, a bottom sheet on phones, see RoomView).
 */
export function PanelChrome({
  title,
  icon,
  count,
  onClose,
  children,
  actions,
}: {
  title: string;
  icon?: ReactNode;
  /** Shown as a chip next to the title (e.g. how many people are here). */
  count?: number | null;
  onClose: () => void;
  children: ReactNode;
  actions?: ReactNode;
}) {
  // Opening a panel moves focus into it (keyboard and screen-reader users land on its title).
  const headingRef = useRef<HTMLHeadingElement>(null);
  useEffect(() => {
    headingRef.current?.focus({ preventScroll: true });
  }, []);
  return (
    <div className="flex h-full min-h-0 flex-col">
      {/* Grab handle of the bottom sheet (phones). */}
      <div className="flex justify-center pt-2 md:hidden" aria-hidden="true">
        <span className="h-1 w-10 rounded-full bg-white/20" />
      </div>
      <div className="flex items-center gap-2.5 border-b border-white/10 px-4 py-3">
        {icon && <span className="flex size-8 shrink-0 items-center justify-center rounded-lg bg-white/5 text-neutral-300 [&_svg]:size-4">{icon}</span>}
        <h2 ref={headingRef} tabIndex={-1} className="min-w-0 truncate text-[15px] font-semibold tracking-tight text-neutral-50 outline-none">
          {title}
        </h2>
        {count != null && (
          <span className="rounded-full bg-white/10 px-2 py-0.5 text-xs font-semibold tabular-nums text-neutral-200" aria-label={`${count} in total`}>
            {count}
          </span>
        )}
        <div className="ml-auto flex items-center gap-1">
          {actions}
          <Button
            variant="ghost"
            size="icon"
            className="size-8 rounded-lg text-neutral-300 hover:bg-white/10 hover:text-white focus-visible:ring-offset-neutral-900"
            aria-label={`Close ${title.toLowerCase()}`}
            onClick={onClose}
          >
            <ChevronDown className="md:hidden" aria-hidden="true" />
            <X className="hidden md:block" aria-hidden="true" />
          </Button>
        </div>
      </div>
      <div className="min-h-0 flex-1 overflow-y-auto">{children}</div>
    </div>
  );
}

export const darkInput =
  'h-10 rounded-lg border-white/15 bg-neutral-800 text-neutral-100 placeholder:text-neutral-400 focus-visible:ring-offset-neutral-900';

/** A titled group inside a panel (host controls). */
export function PanelSection({ title, description, children }: { title: string; description?: ReactNode; children: ReactNode }) {
  return (
    <section className="rounded-xl border border-white/10 bg-white/[0.03] p-4" aria-label={title}>
      <h3 className="text-sm font-semibold text-neutral-100">{title}</h3>
      {description && <p className="mt-0.5 text-xs leading-relaxed text-neutral-400">{description}</p>}
      <div className="mt-3">{children}</div>
    </section>
  );
}

/** One setting: label and hint on the left, the control on the right, aligned on every row. */
export function SettingRow({ id, label, hint, children }: { id?: string; label: ReactNode; hint?: ReactNode; children: ReactNode }) {
  return (
    <div className="flex items-start justify-between gap-4">
      <div className="min-w-0">
        <label htmlFor={id} className="block text-sm font-medium text-neutral-100">
          {label}
        </label>
        {hint && <p className="mt-0.5 text-xs leading-relaxed text-neutral-400">{hint}</p>}
      </div>
      <div className="shrink-0 pt-0.5">{children}</div>
    </div>
  );
}
