// In-memory repository for core behavior tests only.

import { IDbRepository } from '../src/storage/repository';
import {
  UserRow,
  AssignmentRow,
  SyncRunRow,
  NotificationDeliveryRow,
  PushPlusSettingsRow,
  BindingState,
} from '../src/storage/types';

export class MockRepository implements IDbRepository {
  users = new Map<string, UserRow>(); // id -> UserRow
  assignments = new Map<string, AssignmentRow>(); // `${userId}:${assignmentId}` -> AssignmentRow
  syncRuns: SyncRunRow[] = [];
  notificationDeliveries = new Map<string, NotificationDeliveryRow>(); // dedupeKey -> NotificationDeliveryRow
  pushPlusSettings = new Map<string, PushPlusSettingsRow>(); // userId -> PushPlusSettingsRow

  async getUserById(id: string): Promise<UserRow | null> {
    return this.users.get(id) || null;
  }

  async updateUserBindingState(userId: string, state: BindingState, errorCode: string | null, nowIso: string): Promise<void> {
    const user = this.users.get(userId);
    if (user) {
      user.binding_state = state;
      user.last_sync_error_code = errorCode;
      user.updated_at = nowIso;
    }
  }

  async acquireSyncLease(userId: string, leaseDurationMs = 120000): Promise<boolean> {
    const user = this.users.get(userId);
    if (!user) return false;
    const now = Date.now();
    if (user.syncing_until > now) {
      return false; // already locked
    }
    user.syncing_until = now + leaseDurationMs;
    return true;
  }

  async releaseSyncLease(userId: string): Promise<void> {
    const user = this.users.get(userId);
    if (user) {
      user.syncing_until = 0;
    }
  }

  async recordSyncSuccess(userId: string, nowIso: string, isFullSuccess = true): Promise<void> {
    const user = this.users.get(userId);
    if (user) {
      if (isFullSuccess) {
        user.snapshot_state = 'INITIALIZED';
        user.quiz_snapshot_state = 'INITIALIZED';
      }
      user.last_sync_at = nowIso;
      user.last_sync_success_at = nowIso;
      user.last_sync_error_code = null;
      user.updated_at = nowIso;
    }
  }

  async recordSyncFailure(userId: string, errorCode: string, nowIso: string): Promise<void> {
    const user = this.users.get(userId);
    if (user) {
      user.last_sync_at = nowIso;
      user.last_sync_error_code = errorCode;
      user.updated_at = nowIso;
    }
  }

  async getActiveAssignments(userId: string): Promise<AssignmentRow[]> {
    return Array.from(this.assignments.values())
      .filter((a) => a.user_id === userId && a.state === 'ACTIVE')
      .sort((a, b) => (a.raw_deadline || '').localeCompare(b.raw_deadline || ''));
  }

  async getAllAssignments(userId: string): Promise<AssignmentRow[]> {
    return Array.from(this.assignments.values()).filter((a) => a.user_id === userId);
  }

  async upsertAssignment(record: AssignmentRow): Promise<void> {
    const key = `${record.user_id}:${record.assignment_id}`;
    this.assignments.set(key, { ...record });
  }

  async markAssignmentRemoved(userId: string, assignmentId: string, removedAt: string): Promise<void> {
    const key = `${userId}:${assignmentId}`;
    const existing = this.assignments.get(key);
    if (existing) {
      existing.state = 'REMOVED';
      existing.removed_at = removedAt;
      existing.updated_at = removedAt;
    }
  }

  async recordSyncRun(run: SyncRunRow): Promise<void> {
    this.syncRuns.push({ ...run });
  }

  async hasNotificationBeenDelivered(dedupeKey: string): Promise<boolean> {
    const existing = this.notificationDeliveries.get(dedupeKey);
    return existing !== undefined && existing.status === 'SUCCESS';
  }

  async recordNotificationDelivery(delivery: NotificationDeliveryRow): Promise<void> {
    this.notificationDeliveries.set(delivery.dedupe_key, { ...delivery });
  }

  async getPushPlusSettings(userId: string): Promise<PushPlusSettingsRow | null> {
    return this.pushPlusSettings.get(userId) || null;
  }

  async upsertPushPlusSettings(settings: PushPlusSettingsRow): Promise<void> {
    const existing = this.pushPlusSettings.get(settings.user_id);
    if (existing) {
      existing.enabled = settings.enabled;
      if (settings.token_ciphertext) existing.token_ciphertext = settings.token_ciphertext;
      if (settings.token_iv) existing.token_iv = settings.token_iv;
      existing.notify_new_assignment = settings.notify_new_assignment;
      existing.notify_deadline_change = settings.notify_deadline_change;
      existing.remind_24h = settings.remind_24h;
      existing.remind_3h = settings.remind_3h;
      existing.reminder_baseline_at = settings.reminder_baseline_at;
      existing.remind_24h_baseline_at = settings.remind_24h_baseline_at;
      existing.remind_3h_baseline_at = settings.remind_3h_baseline_at;
      existing.updated_at = settings.updated_at;
    } else {
      this.pushPlusSettings.set(settings.user_id, { ...settings });
    }
  }
}
