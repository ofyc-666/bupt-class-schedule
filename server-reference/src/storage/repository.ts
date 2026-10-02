import {
  AssignmentRow,
  SyncRunRow,
  NotificationDeliveryRow,
  PushPlusSettingsRow,
  BindingState,
} from './types';

export interface IDbRepository {
  updateUserBindingState(userId: string, state: BindingState, errorCode: string | null, nowIso: string): Promise<void>;
  acquireSyncLease(userId: string, leaseDurationMs?: number): Promise<boolean>;
  releaseSyncLease(userId: string): Promise<void>;
  recordSyncSuccess(userId: string, nowIso: string, isFullSuccess?: boolean): Promise<void>;
  recordSyncFailure(userId: string, errorCode: string, nowIso: string): Promise<void>;
  getActiveAssignments(userId: string): Promise<AssignmentRow[]>;
  upsertAssignment(record: AssignmentRow): Promise<void>;
  markAssignmentRemoved(userId: string, assignmentId: string, removedAt: string): Promise<void>;
  recordSyncRun(run: SyncRunRow): Promise<void>;
  hasNotificationBeenDelivered(dedupeKey: string): Promise<boolean>;
  recordNotificationDelivery(delivery: NotificationDeliveryRow): Promise<void>;
  getPushPlusSettings(userId: string): Promise<PushPlusSettingsRow | null>;
}
