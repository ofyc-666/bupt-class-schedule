export type BindingState = 'UNBOUND' | 'BINDING' | 'BOUND' | 'NEEDS_REAUTH';
export type SnapshotState = 'UNINITIALIZED' | 'INITIALIZED';
export type AssignmentState = 'ACTIVE' | 'REMOVED';

export interface UserRow {
  id: string;
  account: string;
  api_token_hash: string;
  ucloud_password_ciphertext: string | null;
  ucloud_password_iv: string | null;
  binding_state: BindingState;
  snapshot_state: SnapshotState;
  quiz_snapshot_state?: SnapshotState;
  syncing_until: number;
  created_at: string;
  updated_at: string;
  last_sync_at: string | null;
  last_sync_success_at: string | null;
  next_sync_at: string | null;
  last_sync_error_code: string | null;
}

export interface AssignmentRow {
  user_id: string;
  assignment_id: string;
  task_type?: 'ASSIGNMENT' | 'QUIZ';
  site_id: string | null;
  course_name: string;
  title: string;
  chapter_name: string | null;
  raw_deadline: string | null;
  assignment_status: number;
  state: AssignmentState;
  first_seen_at: string;
  last_seen_at: string;
  removed_at: string | null;
  updated_at: string;
  raw_hash: string;
}

export interface SyncRunRow {
  id: string;
  user_id: string;
  started_at: string;
  finished_at: string | null;
  result: 'SUCCESS' | 'FAILED' | 'PARTIAL';
  assignment_count: number;
  new_count: number;
  updated_count: number;
  removed_count: number;
  error_code: string | null;
}

export interface NotificationDeliveryRow {
  id: string;
  user_id: string;
  assignment_id: string | null;
  notification_type: 'NEW' | 'DEADLINE_CHANGED' | 'REMIND_24H' | 'REMIND_3H' | 'TEST';
  dedupe_key: string;
  sent_at: string;
  status: 'SUCCESS' | 'FAILED';
  error_message: string | null;
}

export interface PushPlusSettingsRow {
  user_id: string;
  enabled: number; // 0 or 1
  token_ciphertext: string | null;
  token_iv: string | null;
  notify_new_assignment: number; // 0 or 1
  notify_deadline_change: number; // 0 or 1
  remind_24h: number; // 0 or 1
  remind_3h: number; // 0 or 1
  reminder_baseline_at: string | null;
  remind_24h_baseline_at: string | null;
  remind_3h_baseline_at: string | null;
  updated_at: string;
}
