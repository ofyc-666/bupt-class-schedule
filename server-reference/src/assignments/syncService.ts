import { IDbRepository } from '../storage/repository';
import { UserRow, AssignmentRow } from '../storage/types';
import { UCloudError, UCloudSession } from '../ucloud/client';
import { AssignmentSource } from './ports';
import { NotificationSender } from '../notifications/types';
import { decryptText, sha256Hex } from '../crypto/encryption';

export type SyncTrigger = 'SCHEDULED' | 'MANUAL' | 'BIND';

export interface SyncResult {
  status: 'SUCCESS' | 'REUSED' | 'FAILED' | 'PARTIAL';
  assignmentCount: number;
  newCount: number;
  updatedCount: number;
  removedCount: number;
  errorCode?: string;
  errorMessage?: string;
}

function safeErrorCode(error: unknown): string {
  const allowed = new Set([
    'INVALID_CREDENTIALS', 'REAUTH_REQUIRED', 'UPSTREAM_NETWORK_ERROR',
    'UPSTREAM_PROTOCOL_CHANGED', 'INVALID_RESPONSE', 'INTERNAL_ERROR',
  ]);
  const code = error instanceof UCloudError ? error.code : '';
  return allowed.has(code) ? code : 'UPSTREAM_NETWORK_ERROR';
}

export function parseAsiaShanghaiDeadlineMs(rawDeadline: string | null): number | null {
  if (!rawDeadline) return null;
  const trimmed = rawDeadline.trim();
  if (!trimmed) return null;

  // Format: YYYY-MM-DD HH:mm or YYYY-MM-DD HH:mm:ss
  const isoLike = trimmed.replace(' ', 'T');
  const withSeconds = isoLike.length === 16 ? `${isoLike}:00` : isoLike;
  const withTz = `${withSeconds}+08:00`;
  const time = Date.parse(withTz);
  return Number.isNaN(time) ? null : time;
}

export class SyncService {
  constructor(
    private dbRepo: IDbRepository,
    private ucloudClient: AssignmentSource,
    private notificationSender: NotificationSender | null,
    private masterKey: string
  ) {}

  async syncUserAssignments(
    user: UserRow,
    trigger: SyncTrigger,
    options: {
      passwordOverride?: string;
      isBaselineOverride?: boolean;
      sessionOverride?: UCloudSession;
    } = {}
  ): Promise<SyncResult> {
    const now = new Date();
    const nowIso = now.toISOString();
    const runId = crypto.randomUUID();

    // 1. Single-flight lease lock
    const leaseAcquired = await this.dbRepo.acquireSyncLease(user.id);
    if (!leaseAcquired) {
      return {
        status: 'REUSED',
        assignmentCount: 0,
        newCount: 0,
        updatedCount: 0,
        removedCount: 0,
        errorMessage: '已有同步任务正在进行中',
      };
    }

    try {
      // 2. Obtain session: reuse sessionOverride if provided, otherwise decrypt and login
      let session: UCloudSession;
      if (options.sessionOverride) {
        session = options.sessionOverride;
      } else {
        let ucloudPassword = options.passwordOverride;
        if (!ucloudPassword) {
          if (!user.ucloud_password_ciphertext || !user.ucloud_password_iv) {
            throw new UCloudError('INVALID_CREDENTIALS', '未绑定云邮密码');
          }
          try {
            ucloudPassword = await decryptText(
              user.ucloud_password_ciphertext,
              user.ucloud_password_iv,
              this.masterKey
            );
          } catch {
            throw new UCloudError('INTERNAL_ERROR', '密码解密失败');
          }
        }

        // 3. Login to UCloud via CAS
        try {
          session = await this.ucloudClient.login(user.account, ucloudPassword);
        } catch (err: unknown) {
          const errCode = safeErrorCode(err);
          if (errCode === 'INVALID_CREDENTIALS' || errCode === 'REAUTH_REQUIRED') {
            await this.dbRepo.updateUserBindingState(user.id, 'NEEDS_REAUTH', errCode, nowIso);
          }
          await this.dbRepo.recordSyncFailure(user.id, errCode, nowIso);
        await this.dbRepo.recordSyncRun({
          id: runId,
          user_id: user.id,
          started_at: nowIso,
          finished_at: new Date().toISOString(),
          result: 'FAILED',
          assignment_count: 0,
          new_count: 0,
          updated_count: 0,
          removed_count: 0,
          error_code: errCode,
        });
          return {
            status: 'FAILED',
            assignmentCount: 0,
            newCount: 0,
            updatedCount: 0,
            removedCount: 0,
            errorCode: errCode,
            errorMessage: '云邮登录失败，请检查账号或网络',
          };
        }
      }

      // 4. Fetch assignments
      let fetchResult;
      try {
        fetchResult = await this.ucloudClient.fetchAll(session);
      } catch (err: unknown) {
        const errCode = safeErrorCode(err);
        if (errCode === 'INVALID_CREDENTIALS' || errCode === 'REAUTH_REQUIRED') {
          await this.dbRepo.updateUserBindingState(user.id, 'NEEDS_REAUTH', errCode, nowIso);
        }
        await this.dbRepo.recordSyncFailure(user.id, errCode, nowIso);
        await this.dbRepo.recordSyncRun({
          id: runId,
          user_id: user.id,
          started_at: nowIso,
          finished_at: new Date().toISOString(),
          result: 'FAILED',
          assignment_count: 0,
          new_count: 0,
          updated_count: 0,
          removed_count: 0,
          error_code: errCode,
        });
        return {
          status: 'FAILED',
          assignmentCount: 0,
          newCount: 0,
          updatedCount: 0,
          removedCount: 0,
          errorCode: errCode,
          errorMessage: '云邮任务读取失败，请稍后重试',
        };
      }

      // 5. Diff & Baseline logic
      const isBaseline = options.isBaselineOverride ?? (user.snapshot_state === 'UNINITIALIZED');
      const isQuizBaseline = options.isBaselineOverride ?? (user.quiz_snapshot_state !== 'INITIALIZED');
      const oldActiveAssignments = await this.dbRepo.getActiveAssignments(user.id);
      const oldMap = new Map<string, AssignmentRow>();
      for (const a of oldActiveAssignments) {
        oldMap.set(a.assignment_id, a);
      }

      let newCount = 0;
      let updatedCount = 0;
      let removedCount = 0;

      const newAssignmentIds = new Set<string>();
      const newlyCreatedAssignmentIds = new Set<string>();

      // Notification settings are not needed when no sender was supplied.
      const pushPlusSettings = this.notificationSender
        ? await this.dbRepo.getPushPlusSettings(user.id)
        : null;
      let pushPlusToken: string | null = null;
      if (this.notificationSender && pushPlusSettings && pushPlusSettings.enabled === 1 && pushPlusSettings.token_ciphertext && pushPlusSettings.token_iv) {
        try {
          pushPlusToken = await decryptText(
            pushPlusSettings.token_ciphertext,
            pushPlusSettings.token_iv,
            this.masterKey
          );
        } catch {
          // Token decryption failed; PushPlus will be skipped safely
        }
      }

      // Process new snapshot
      for (const rawItem of fetchResult.assignments) {
        const taskType = rawItem.taskType || 'ASSIGNMENT';
        const itemIsBaseline = taskType === 'QUIZ' ? isQuizBaseline : isBaseline;
        newAssignmentIds.add(rawItem.assignmentId);
        const rawHash = await sha256Hex(
          `${taskType === 'QUIZ' ? 'QUIZ:' : ''}${rawItem.assignmentId}:${rawItem.title}:${rawItem.courseName}:${rawItem.rawDeadline || ''}:${rawItem.chapterName || ''}`
        );

        const existing = oldMap.get(rawItem.assignmentId);
        if (!existing) {
          // NEW assignment
          newCount++;
          newlyCreatedAssignmentIds.add(rawItem.assignmentId);
          await this.dbRepo.upsertAssignment({
            user_id: user.id,
            assignment_id: rawItem.assignmentId,
            task_type: taskType,
            site_id: rawItem.siteId,
            course_name: rawItem.courseName,
            title: rawItem.title,
            chapter_name: rawItem.chapterName,
            raw_deadline: rawItem.rawDeadline,
            assignment_status: 99,
            state: 'ACTIVE',
            first_seen_at: nowIso,
            last_seen_at: nowIso,
            removed_at: null,
            updated_at: nowIso,
            raw_hash: rawHash,
          });

          // Send PushPlus notification ONLY IF NOT BASELINE
          if (!itemIsBaseline && pushPlusToken && this.notificationSender && pushPlusSettings?.notify_new_assignment === 1) {
            await this.deliverNotificationSafely({
              userId: user.id,
              assignmentId: rawItem.assignmentId,
              notificationType: 'NEW',
              rawDeadline: rawItem.rawDeadline,
              pushPlusToken,
              messagePayload: this.notificationSender.formatNewAssignmentMessage(
                rawItem.courseName,
                rawItem.title,
                rawItem.rawDeadline,
                rawItem.chapterName,
                taskType
              ),
            });
          }
        } else {
          // Existing assignment - check for updates
          const deadlineChanged = existing.raw_deadline !== rawItem.rawDeadline;
          const contentChanged = existing.raw_hash !== rawHash;

          if (deadlineChanged || contentChanged) {
            updatedCount++;
            await this.dbRepo.upsertAssignment({
              user_id: user.id,
              assignment_id: rawItem.assignmentId,
              task_type: taskType,
              site_id: rawItem.siteId,
              course_name: rawItem.courseName,
              title: rawItem.title,
              chapter_name: rawItem.chapterName,
              raw_deadline: rawItem.rawDeadline,
              assignment_status: 99,
              state: 'ACTIVE',
              first_seen_at: existing.first_seen_at,
              last_seen_at: nowIso,
              removed_at: null,
              updated_at: nowIso,
              raw_hash: rawHash,
            });

            if (!itemIsBaseline && deadlineChanged && pushPlusToken && this.notificationSender && pushPlusSettings?.notify_deadline_change === 1) {
              await this.deliverNotificationSafely({
                userId: user.id,
                assignmentId: rawItem.assignmentId,
                notificationType: 'DEADLINE_CHANGED',
                rawDeadline: rawItem.rawDeadline,
                pushPlusToken,
                messagePayload: this.notificationSender.formatDeadlineChangedMessage(
                  rawItem.courseName,
                  rawItem.title,
                  existing.raw_deadline,
                  rawItem.rawDeadline,
                  taskType
                ),
              });
            }
          } else {
            // Keep active, refresh last_seen_at; keep updated_at unchanged
            await this.dbRepo.upsertAssignment({
              ...existing,
              last_seen_at: nowIso,
              updated_at: existing.updated_at,
            });
          }
        }
      }

      // Check for REMOVED assignments
      // Crucial: If all courses succeeded, any missing assignment is REMOVED (even if its course disappeared from the system).
      // If partial sync, remove only when that course's matching task source succeeded.
      for (const oldItem of oldActiveAssignments) {
        if (!newAssignmentIds.has(oldItem.assignment_id)) {
          const siteId = oldItem.site_id;
          const successfulSourceSiteIds = oldItem.task_type === 'QUIZ'
            ? (fetchResult.successfulQuizSiteIds ?? fetchResult.successfulSiteIds)
            : (fetchResult.successfulAssignmentSiteIds ?? fetchResult.successfulSiteIds);
          const shouldRemove = fetchResult.allCoursesSucceeded
            ? true
            : (siteId && successfulSourceSiteIds.has(siteId));
          if (shouldRemove) {
            removedCount++;
            await this.dbRepo.markAssignmentRemoved(user.id, oldItem.assignment_id, nowIso);
            // Rule: DO NOT send PushPlus for removed assignments!
          }
        }
      }

      // Record sync success in user record (only initialize snapshot if full success)
      await this.dbRepo.recordSyncSuccess(user.id, nowIso, fetchResult.allCoursesSucceeded);

      // Check due reminders (24h / 3h)
      if (pushPlusToken && this.notificationSender && pushPlusSettings && pushPlusSettings.enabled === 1) {
        await this.checkDueReminders(
          user.id,
          pushPlusToken,
          pushPlusSettings,
          newlyCreatedAssignmentIds,
          isBaseline,
          isQuizBaseline
        );
      }

      // Record sync run
      const runResult = fetchResult.allCoursesSucceeded ? 'SUCCESS' : 'PARTIAL';
      await this.dbRepo.recordSyncRun({
        id: runId,
        user_id: user.id,
        started_at: nowIso,
        finished_at: new Date().toISOString(),
        result: runResult,
        assignment_count: fetchResult.assignments.length,
        new_count: newCount,
        updated_count: updatedCount,
        removed_count: removedCount,
        error_code: fetchResult.allCoursesSucceeded ? null : 'PARTIAL_SYNC',
      });

      return {
        status: runResult,
        assignmentCount: fetchResult.assignments.length,
        newCount,
        updatedCount,
        removedCount,
      };
    } finally {
      await this.dbRepo.releaseSyncLease(user.id);
    }
  }

  private async checkDueReminders(
    userId: string,
    token: string,
    settings: {
      remind_24h: number;
      remind_3h: number;
      reminder_baseline_at?: string | null;
      remind_24h_baseline_at?: string | null;
      remind_3h_baseline_at?: string | null;
    },
    newlyCreatedAssignmentIds: Set<string>,
    isBaseline: boolean,
    isQuizBaseline: boolean
  ): Promise<void> {
    const sender = this.notificationSender;
    if (!sender) return;
    const activeAssignments = await this.dbRepo.getActiveAssignments(userId);
    const nowMs = Date.now();
    const baseline24hStr = settings.remind_24h_baseline_at || settings.reminder_baseline_at;
    const baseline24hMs = baseline24hStr ? Date.parse(baseline24hStr) : 0;
    const baseline3hStr = settings.remind_3h_baseline_at || settings.reminder_baseline_at;
    const baseline3hMs = baseline3hStr ? Date.parse(baseline3hStr) : 0;

    for (const a of activeAssignments) {
      if ((a.task_type === 'QUIZ' ? isQuizBaseline : isBaseline)) continue;
      // Do not remind again when an assignment was first discovered in this sync.
      if (newlyCreatedAssignmentIds.has(a.assignment_id)) {
        continue;
      }

      const deadlineMs = parseAsiaShanghaiDeadlineMs(a.raw_deadline);
      if (!deadlineMs) continue;

      // Rule: Do not remind OVERDUE assignments
      if (deadlineMs <= nowMs) continue;

      const msRemaining = deadlineMs - nowMs;
      const hoursRemaining = msRemaining / (1000 * 60 * 60);
      const firstSeenMs = a.first_seen_at ? Date.parse(a.first_seen_at) : 0;

      // 3h reminder (must have been monitored/enabled at least 3h before deadline)
      const effectiveBaseline3hMs = Math.max(baseline3hMs, firstSeenMs);
      if (
        settings.remind_3h === 1 &&
        hoursRemaining <= 3 &&
        hoursRemaining > 0 &&
        effectiveBaseline3hMs <= deadlineMs - 3 * 3600 * 1000
      ) {
        await this.deliverNotificationSafely({
          userId,
          assignmentId: a.assignment_id,
          notificationType: 'REMIND_3H',
          rawDeadline: a.raw_deadline,
          pushPlusToken: token,
          messagePayload: sender.formatDueReminderMessage(
            a.course_name,
            a.title,
            a.raw_deadline || '',
            3,
            a.task_type || 'ASSIGNMENT'
          ),
        });
      }
      // 24h reminder (must have been monitored/enabled at least 24h before deadline)
      else if (
        settings.remind_24h === 1 &&
        hoursRemaining <= 24 &&
        hoursRemaining > 3 &&
        Math.max(baseline24hMs, firstSeenMs) <= deadlineMs - 24 * 3600 * 1000
      ) {
        await this.deliverNotificationSafely({
          userId,
          assignmentId: a.assignment_id,
          notificationType: 'REMIND_24H',
          rawDeadline: a.raw_deadline,
          pushPlusToken: token,
          messagePayload: sender.formatDueReminderMessage(
            a.course_name,
            a.title,
            a.raw_deadline || '',
            24,
            a.task_type || 'ASSIGNMENT'
          ),
        });
      }
    }
  }

  private async deliverNotificationSafely(params: {
    userId: string;
    assignmentId: string;
    notificationType: 'NEW' | 'DEADLINE_CHANGED' | 'REMIND_24H' | 'REMIND_3H';
    rawDeadline: string | null;
    pushPlusToken: string;
    messagePayload: { title: string; content: string };
  }): Promise<void> {
    if (!this.notificationSender) return;
    const dedupeKey = await sha256Hex(
      `${params.userId}:${params.assignmentId}:${params.rawDeadline || ''}:${params.notificationType}`
    );

    const alreadySent = await this.dbRepo.hasNotificationBeenDelivered(dedupeKey);
    if (alreadySent) {
      return;
    }

    const sendRes = await this.notificationSender.send(
      params.pushPlusToken,
      params.messagePayload.title,
      params.messagePayload.content
    );

    await this.dbRepo.recordNotificationDelivery({
      id: crypto.randomUUID(),
      user_id: params.userId,
      assignment_id: params.assignmentId,
      notification_type: params.notificationType,
      dedupe_key: dedupeKey,
      sent_at: new Date().toISOString(),
      status: sendRes.success ? 'SUCCESS' : 'FAILED',
      error_message: sendRes.success ? null : 'NOTIFICATION_FAILED',
    });
  }
}
