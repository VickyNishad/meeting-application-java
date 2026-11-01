import { formatDuration } from '@/lib/utils';

export interface SpeakerShare {
  name: string;
  speakingMs: number;
  /** Share of the meeting's total speaking time, 0–100. */
  sharePercent: number;
}

/**
 * Who spoke how much: one horizontal bar per person (a single series, so one hue and no legend), longest first.
 * Values are written next to each bar so the chart reads without colour; hovering a bar shows the exact figures,
 * and the list itself is the accessible table view.
 */
export function SpeakingShare({ speakers, label }: { speakers: SpeakerShare[]; label: string }) {
  if (speakers.length === 0) return <p className="text-sm text-muted-foreground">No speaking time was recorded.</p>;
  const max = Math.max(...speakers.map((s) => s.sharePercent), 1);
  return (
    <ul className="grid gap-2" aria-label={label}>
      {speakers.map((s, i) => {
        const detail = `${s.name}: ${s.sharePercent.toFixed(1)}% of speaking time (${formatDuration(Math.round(s.speakingMs / 1000))})`;
        return (
          <li key={`${s.name}-${i}`} className="grid grid-cols-[minmax(0,8rem)_1fr_auto] items-center gap-2 text-sm" title={detail}>
            <span className="truncate text-foreground">{s.name}</span>
            <span className="relative h-3 rounded-r bg-muted/60" aria-hidden="true">
              <span
                className="absolute inset-y-0 left-0 rounded-r bg-primary transition-[width] hover:brightness-110"
                style={{ width: `${Math.max(2, (s.sharePercent / max) * 100)}%` }}
              />
            </span>
            <span className="tabular-nums text-muted-foreground">
              {s.sharePercent.toFixed(0)}%<span className="sr-only"> — {detail}</span>
            </span>
          </li>
        );
      })}
    </ul>
  );
}
