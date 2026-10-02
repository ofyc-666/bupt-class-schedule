export interface PushPlusPreferences {
  notifyNewAssignment: boolean;
  notifyDeadlineChange: boolean;
  remind24h: boolean;
  remind3h: boolean;
}

export const DEFAULT_PUSHPLUS_PREFERENCES: PushPlusPreferences = {
  notifyNewAssignment: true,
  notifyDeadlineChange: false,
  remind24h: false,
  remind3h: true,
};

export function resolvePushPlusPreferences(
  input: Partial<PushPlusPreferences>,
  fallback: PushPlusPreferences = DEFAULT_PUSHPLUS_PREFERENCES
): PushPlusPreferences {
  return {
    notifyNewAssignment: typeof input.notifyNewAssignment === 'boolean'
      ? input.notifyNewAssignment
      : fallback.notifyNewAssignment,
    notifyDeadlineChange: typeof input.notifyDeadlineChange === 'boolean'
      ? input.notifyDeadlineChange
      : fallback.notifyDeadlineChange,
    remind24h: typeof input.remind24h === 'boolean' ? input.remind24h : fallback.remind24h,
    remind3h: typeof input.remind3h === 'boolean' ? input.remind3h : fallback.remind3h,
  };
}
