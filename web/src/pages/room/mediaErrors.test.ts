import { describe, expect, it } from 'vitest';
import { ConnectionError, ConnectionErrorReason, MediaDeviceFailure } from 'livekit-client';
import { deviceFailureMessage, isMediaDeviceError } from './mediaErrors';

describe('media device errors (W-07)', () => {
  it('tells device failures apart from connection errors', () => {
    expect(isMediaDeviceError(new DOMException('denied', 'NotAllowedError'))).toBe(true);
    expect(isMediaDeviceError(new DOMException('none', 'NotFoundError'))).toBe(true);
    expect(isMediaDeviceError(new DOMException('busy', 'NotReadableError'))).toBe(true);
    expect(isMediaDeviceError(new ConnectionError('could not establish signal connection', ConnectionErrorReason.ServerUnreachable))).toBe(false);
    expect(isMediaDeviceError(new Error('boom'))).toBe(false);
    expect(isMediaDeviceError(null)).toBe(false);
  });

  it('builds a non-fatal notice per device', () => {
    expect(deviceFailureMessage(MediaDeviceFailure.PermissionDenied, 'audioinput')).toMatch(/permission to use the microphone was denied/i);
    expect(deviceFailureMessage(MediaDeviceFailure.NotFound, 'videoinput')).toMatch(/no camera was found/i);
    expect(deviceFailureMessage(MediaDeviceFailure.DeviceInUse, 'videoinput')).toMatch(/camera is in use/i);
    expect(deviceFailureMessage(undefined, 'audioinput')).toMatch(/could not start the microphone/i);
  });
});
