import { useSyncExternalStore } from 'react';

/**
 * The user's "Noise reduction" choice (on unless turned off), kept in localStorage like the theme so that it holds
 * for the next meeting. Shared by the pre-join screen and the in-meeting microphone menu.
 */
const STORAGE_KEY = 'mp-noise-reduction';
const listeners = new Set<() => void>();

function read(): boolean {
  try {
    return window.localStorage.getItem(STORAGE_KEY) !== 'off';
  } catch {
    return true;
  }
}

let current = read();

export function isNoiseReductionOn(): boolean {
  return current;
}

export function setNoiseReduction(on: boolean): void {
  current = on;
  try {
    window.localStorage.setItem(STORAGE_KEY, on ? 'on' : 'off');
  } catch {
    // storage blocked: the choice holds for this page only
  }
  listeners.forEach((l) => l());
}

export function useNoiseReduction(): [boolean, (on: boolean) => void] {
  const on = useSyncExternalStore(
    (l) => {
      listeners.add(l);
      return () => listeners.delete(l);
    },
    isNoiseReductionOn,
    isNoiseReductionOn,
  );
  return [on, setNoiseReduction];
}
