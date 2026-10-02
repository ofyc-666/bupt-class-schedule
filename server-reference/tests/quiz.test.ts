import { afterEach, describe, expect, it, vi } from 'vitest';
import { UCloudClient, UCloudError, RawUCloudAssignment } from '../src/ucloud/client';
import { MockRepository } from './mockRepository';
import { SyncService } from '../src/assignments/syncService';
import { encryptText } from '../src/crypto/encryption';
import { PushPlusClient } from '../src/notifications/pushplus';
import { UserRow } from '../src/storage/types';

const session = { accessToken: 'test-token', userId: 'test-user' };
const course = { siteId: 'site-a', siteName: '形式语言', raw: {} };
const masterKey = '0b60449fa3da9fc88b4cd40c0eb4cb3e480bd2a1d212f27acf20154dac31d542';

afterEach(() => { vi.unstubAllGlobals(); vi.useRealTimers(); });

describe('UCloud quizzes', () => {
  it('fetches every page, keeps only 未提交 even when state=1, and uses endAt', async () => {
    const requested: URL[] = [];
    vi.stubGlobal('fetch', vi.fn(async (input: string, init: RequestInit) => {
      const url = new URL(input);
      requested.push(url);
      expect((init.headers as Record<string, string>)['Blade-Auth']).toBe('test-token');
      const page = Number(url.searchParams.get('current'));
      return new Response(JSON.stringify({ code: 200, success: true, data: {
        records: page === 1
          ? [{ id: 'q1', title: '形式语言基础', state: 1, statusSelf: '未提交', endAt: '2026-09-27 23:59:00' },
            { id: 'done', title: 'Unit 1', state: 1, statusSelf: '已评分', endAt: '2026-09-27 23:59:00' }]
          : [{ id: 'q2', title: '第二页', state: 1, statusSelf: '未提交', endAt: '2026-09-28 12:00:00' }],
        total: 3, size: 2, current: page, pages: 2,
      } }), { status: 200 });
    }));
    const result = await new UCloudClient().fetchQuizzesForCourse(session, course);
    expect(result.map(q => q.assignmentId)).toEqual(['QUIZ:q1', 'QUIZ:q2']);
    expect(result[0].rawDeadline).toBe('2026-09-27 23:59:00');
    expect(result.every(q => q.taskType === 'QUIZ')).toBe(true);
    expect(requested).toHaveLength(2);
    for (const url of requested) {
      expect(url.pathname).toBe('/ykt-site/examination/list-stu');
      expect(url.searchParams.get('siteId')).toBe('site-a');
      expect(url.searchParams.get('size')).toBe('100');
      expect(url.searchParams.get('status')).toBe('-1');
      expect(url.searchParams.get('statusSelf')).toBe('全部');
    }
  });

  it('propagates quiz auth failure and treats one ordinary course failure as partial', async () => {
    const client = new UCloudClient();
    client.fetchCourses = async () => [course, { siteId: 'site-b', siteName: '英语', raw: {} }];
    client.fetchAssignmentsForCourse = async () => [];
    client.fetchQuizzesForCourse = async (_, c) => {
      if (c.siteId === 'site-a') throw new UCloudError('UPSTREAM_NETWORK_ERROR', 'timeout');
      return [];
    };
    const partial = await client.fetchAll(session);
    expect(partial.failedSiteIds.has('site-a')).toBe(true);
    expect(partial.successfulSiteIds.has('site-b')).toBe(true);
    expect(partial.allCoursesSucceeded).toBe(false);
    client.fetchQuizzesForCourse = async () => { throw new UCloudError('REAUTH_REQUIRED', 'expired'); };
    await expect(client.fetchAll(session)).rejects.toMatchObject({ code: 'REAUTH_REQUIRED' });
  });

  it('recognizes HTTP and business auth failures from the quiz endpoint', async () => {
    const client = new UCloudClient();
    vi.stubGlobal('fetch', vi.fn(async () => new Response('', { status: 403 })));
    await expect(client.fetchQuizzesForCourse(session, course)).rejects.toMatchObject({ code: 'REAUTH_REQUIRED' });
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({
      code: 401, success: false, msg: '登录失效',
    }), { status: 200 })));
    await expect(client.fetchQuizzesForCourse(session, course)).rejects.toMatchObject({ code: 'REAUTH_REQUIRED' });
  });

  it.each(['QUIZ', 'ASSIGNMENT'] as const)(
    'keeps the successful source and its removals when %s fails on the same course',
    async (failedType) => {
      const db = new MockRepository();
      const now = new Date().toISOString();
      const user: UserRow = {
        id: 'partial-user', account: '20260003', api_token_hash: 'hash',
        ucloud_password_ciphertext: null, ucloud_password_iv: null,
        binding_state: 'BOUND', snapshot_state: 'INITIALIZED', quiz_snapshot_state: 'INITIALIZED',
        syncing_until: 0, created_at: now, updated_at: now,
        last_sync_at: null, last_sync_success_at: null, next_sync_at: null, last_sync_error_code: null,
      };
      db.users.set(user.id, user);
      for (const taskType of ['ASSIGNMENT', 'QUIZ'] as const) {
        await db.upsertAssignment({
          user_id: user.id, assignment_id: `${taskType}:old`, task_type: taskType,
          site_id: course.siteId, course_name: course.siteName, title: 'old', chapter_name: null,
          raw_deadline: '2026-09-30 12:00:00', assignment_status: 99, state: 'ACTIVE',
          first_seen_at: now, last_seen_at: now, removed_at: null, updated_at: now, raw_hash: 'old',
        });
      }
      const newItem = (taskType: 'ASSIGNMENT' | 'QUIZ'): RawUCloudAssignment => ({
        taskType, assignmentId: `${taskType}:new`, siteId: course.siteId,
        courseName: course.siteName, title: 'new', chapterName: null,
        rawDeadline: '2026-10-01 12:00:00', assignmentStatus: 99, raw: {},
      });
      const client = new UCloudClient();
      client.fetchCourses = async () => [course];
      client.fetchAssignmentsForCourse = async () => {
        if (failedType === 'ASSIGNMENT') throw new UCloudError('UPSTREAM_NETWORK_ERROR', 'assignment failed');
        return [newItem('ASSIGNMENT')];
      };
      client.fetchQuizzesForCourse = async () => {
        if (failedType === 'QUIZ') throw new UCloudError('UPSTREAM_NETWORK_ERROR', 'quiz failed');
        return [newItem('QUIZ')];
      };
      const fetched = await client.fetchAll(session);
      const successfulType = failedType === 'QUIZ' ? 'ASSIGNMENT' : 'QUIZ';
      expect(fetched.assignments.map(item => item.assignmentId)).toEqual([`${successfulType}:new`]);
      expect(fetched.allCoursesSucceeded).toBe(false);
      const service = new SyncService(db, client, {} as PushPlusClient, masterKey);
      const result = await service.syncUserAssignments(user, 'SCHEDULED', { sessionOverride: session });
      expect(result.status).toBe('PARTIAL');
      expect(result.newCount).toBe(1);
      expect(result.removedCount).toBe(1);
      expect((await db.getActiveAssignments(user.id)).map(item => item.assignment_id).sort())
        .toEqual([`${failedType}:old`, `${successfulType}:new`].sort());
    },
  );

  it.each(['courses', 'assignments', 'quizzes'] as const)(
    'caps requests when %s reports an abnormally large page count', async (source) => {
      let requests = 0;
      vi.stubGlobal('fetch', vi.fn(async () => {
        requests++;
        const records = source === 'courses'
          ? [{ id: 'site-a', siteName: '形式语言' }]
          : source === 'assignments'
            ? [{ id: 'a1', assignmentStatus: 99, title: '作业' }]
            : [{ id: 'q1', statusSelf: '未提交', title: '测验', endAt: '2026-09-27 23:59:00' }];
        return new Response(JSON.stringify({ code: 200, success: true,
          data: { records, current: requests, pages: 999999 } }), { status: 200 });
      }));
      const client = new UCloudClient();
      const request = source === 'courses' ? client.fetchCourses(session)
        : source === 'assignments' ? client.fetchAssignmentsForCourse(session, course)
          : client.fetchQuizzesForCourse(session, course);
      await expect(request).rejects.toMatchObject({ code: 'INVALID_RESPONSE' });
      expect(requests).toBeLessThanOrEqual(20);
    },
  );

  it('baselines existing quizzes, sends NEW for later quizzes, reminds once after crossing 3h, and stops after completion', async () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-09-23T00:00:00Z'));
    const db = new MockRepository();
    const password = await encryptText('password', masterKey);
    const user: UserRow = {
      id: 'u', account: '20260001', api_token_hash: 'hash',
      ucloud_password_ciphertext: password.ciphertext, ucloud_password_iv: password.iv,
      binding_state: 'BOUND', snapshot_state: 'INITIALIZED', quiz_snapshot_state: 'UNINITIALIZED',
      syncing_until: 0, created_at: new Date().toISOString(), updated_at: new Date().toISOString(),
      last_sync_at: null, last_sync_success_at: null, next_sync_at: null, last_sync_error_code: null,
    };
    db.users.set(user.id, user);
    const encrypted = await encryptText('push-token', masterKey);
    await db.upsertPushPlusSettings({
      user_id: user.id, enabled: 1, token_ciphertext: encrypted.ciphertext, token_iv: encrypted.iv,
      notify_new_assignment: 1, notify_deadline_change: 0, remind_24h: 0, remind_3h: 1,
      reminder_baseline_at: null, remind_24h_baseline_at: null, remind_3h_baseline_at: null,
      updated_at: new Date().toISOString(),
    });
    const quiz = (id: string, deadline: string): RawUCloudAssignment => ({
      taskType: 'QUIZ', assignmentId: `QUIZ:${id}`, siteId: 'site-a', courseName: '形式语言',
      title: id, chapterName: null, rawDeadline: deadline, assignmentStatus: 99, raw: {},
    });
    const old = quiz('old', '2026-09-23 09:00:00'); // 1h away at baseline
    const fresh = quiz('fresh', '2026-09-23 12:00:00'); // 4h away at discovery
    let records = [old];
    const ucloud = {
      login: async () => session,
      fetchAll: async () => ({ assignments: records, successfulSiteIds: new Set(['site-a']),
        failedSiteIds: new Set<string>(), allCoursesSucceeded: true }),
    } as unknown as UCloudClient;
    const sent: string[] = [];
    const push = {
      send: async (_token: string, title: string) => { sent.push(title); return { success: true, code: 200, message: 'ok' }; },
      formatNewAssignmentMessage: PushPlusClient.prototype.formatNewAssignmentMessage,
      formatDeadlineChangedMessage: PushPlusClient.prototype.formatDeadlineChangedMessage,
      formatDueReminderMessage: PushPlusClient.prototype.formatDueReminderMessage,
    } as unknown as PushPlusClient;
    const service = new SyncService(db, ucloud, push, masterKey);
    await service.syncUserAssignments(user, 'SCHEDULED');
    expect(sent).toEqual([]);
    expect(user.quiz_snapshot_state).toBe('INITIALIZED');
    expect((await db.getActiveAssignments(user.id))[0].task_type).toBe('QUIZ');

    records = [old, fresh];
    await service.syncUserAssignments(user, 'SCHEDULED');
    expect(sent).toEqual(['【北邮课表】发现新测验']);
    vi.setSystemTime(new Date('2026-09-23T01:30:00Z'));
    await service.syncUserAssignments(user, 'SCHEDULED');
    await service.syncUserAssignments(user, 'SCHEDULED');
    expect(sent.filter(s => s.includes('3小时内'))).toHaveLength(1);
    expect(sent).toHaveLength(2);

    records = [old]; // submitted or graded: no longer in the unfinished snapshot
    await service.syncUserAssignments(user, 'SCHEDULED');
    expect((await db.getActiveAssignments(user.id)).map(a => a.assignment_id)).toEqual(['QUIZ:old']);
    vi.setSystemTime(new Date('2026-09-23T02:00:00Z'));
    await service.syncUserAssignments(user, 'SCHEDULED');
    expect(sent).toHaveLength(2);
  });

  it('retains quizzes from failed courses on partial snapshots and removes them on a later full snapshot', async () => {
    const db = new MockRepository();
    const user: UserRow = {
      id: 'u2', account: '20260002', api_token_hash: 'hash2',
      ucloud_password_ciphertext: null, ucloud_password_iv: null,
      binding_state: 'BOUND', snapshot_state: 'INITIALIZED', quiz_snapshot_state: 'INITIALIZED',
      syncing_until: 0, created_at: new Date().toISOString(), updated_at: new Date().toISOString(),
      last_sync_at: null, last_sync_success_at: null, next_sync_at: null, last_sync_error_code: null,
    };
    db.users.set(user.id, user);
    await db.upsertAssignment({
      user_id: user.id, assignment_id: 'QUIZ:old', task_type: 'QUIZ', site_id: 'site-a',
      course_name: '形式语言', title: '旧测验', chapter_name: null,
      raw_deadline: '2026-09-27 23:59:00', assignment_status: 99, state: 'ACTIVE',
      first_seen_at: new Date().toISOString(), last_seen_at: new Date().toISOString(),
      removed_at: null, updated_at: new Date().toISOString(), raw_hash: 'old',
    });
    let full = false;
    const ucloud = { fetchAll: async () => ({
      assignments: [], successfulSiteIds: full ? new Set(['site-a']) : new Set(['site-b']),
      failedSiteIds: full ? new Set<string>() : new Set(['site-a']), allCoursesSucceeded: full,
    }) } as unknown as UCloudClient;
    const service = new SyncService(db, ucloud, {} as PushPlusClient, masterKey);
    const partial = await service.syncUserAssignments(user, 'SCHEDULED', { sessionOverride: session });
    expect(partial.status).toBe('PARTIAL');
    expect((await db.getActiveAssignments(user.id)).map(a => a.assignment_id)).toEqual(['QUIZ:old']);
    full = true;
    const complete = await service.syncUserAssignments(user, 'SCHEDULED', { sessionOverride: session });
    expect(complete.status).toBe('SUCCESS');
    expect(await db.getActiveAssignments(user.id)).toEqual([]);
  });
});
