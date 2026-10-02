import { describe, expect, it } from 'vitest';
import { DEFAULT_PUSHPLUS_PREFERENCES, resolvePushPlusPreferences } from '../src/notifications/settings';

describe('PushPlus settings defaults', () => {
  it('enables new-assignment and 3h notifications only', () => {
    expect(DEFAULT_PUSHPLUS_PREFERENCES).toEqual({
      notifyNewAssignment: true,
      notifyDeadlineChange: false,
      remind24h: false,
      remind3h: true,
    });
    expect(resolvePushPlusPreferences({})).toEqual(DEFAULT_PUSHPLUS_PREFERENCES);
  });

  it('preserves existing values when a settings update omits notification fields', () => {
    const existing = {
      notifyNewAssignment: false,
      notifyDeadlineChange: true,
      remind24h: true,
      remind3h: false,
    };

    expect(resolvePushPlusPreferences({}, existing)).toEqual(existing);
  });
});
