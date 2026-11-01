import { MediaDeviceFailure } from 'livekit-client';

/** getUserMedia / LiveKit device errors: a missing or blocked device is not a connection failure. */
const MEDIA_DEVICE_ERROR_NAMES = new Set([
  'NotAllowedError',
  'PermissionDeniedError',
  'SecurityError',
  'NotFoundError',
  'DevicesNotFoundError',
  'NotReadableError',
  'TrackStartError',
  'OverconstrainedError',
  'DeviceUnsupportedError',
]);

export function isMediaDeviceError(e: unknown): boolean {
  return typeof e === 'object' && e !== null && 'name' in e && MEDIA_DEVICE_ERROR_NAMES.has(String((e as { name: unknown }).name));
}

export type MediaKind = 'audioinput' | 'videoinput';

/** Non-fatal notice shown when the microphone or camera could not be started in the room. */
export function deviceFailureMessage(failure: MediaDeviceFailure | undefined, kind: MediaKind | undefined): string {
  const what = kind === 'videoinput' ? 'camera' : kind === 'audioinput' ? 'microphone' : 'microphone or camera';
  switch (failure) {
    case MediaDeviceFailure.PermissionDenied:
      return `Permission to use the ${what} was denied, so it stays off. Allow access in your browser's site settings, then turn it on.`;
    case MediaDeviceFailure.NotFound:
      return `No ${what} was found, so it stays off.`;
    case MediaDeviceFailure.DeviceInUse:
      return `The ${what} is in use by another application, so it stays off.`;
    default:
      return `Could not start the ${what}; it stays off. You can try turning it on again.`;
  }
}
