import type { ReactNode } from 'react';
import { X } from 'lucide-react';
import { Button } from '@/components/ui/button';

export function PanelChrome({ title, onClose, children, actions }: { title: string; onClose: () => void; children: ReactNode; actions?: ReactNode }) {
  return (
    <div className="flex h-full min-h-0 flex-col">
      <div className="flex items-center gap-2 border-b border-white/10 px-4 py-3">
        <h2 className="text-sm font-semibold">{title}</h2>
        <div className="ml-auto flex items-center gap-1">
          {actions}
          <Button variant="ghost" size="icon" className="text-neutral-200 hover:bg-white/10 hover:text-white" aria-label={`Close ${title.toLowerCase()}`} onClick={onClose}>
            <X aria-hidden="true" />
          </Button>
        </div>
      </div>
      <div className="min-h-0 flex-1 overflow-y-auto">{children}</div>
    </div>
  );
}

export const darkInput =
  'border-white/15 bg-neutral-800 text-neutral-100 placeholder:text-neutral-400';
