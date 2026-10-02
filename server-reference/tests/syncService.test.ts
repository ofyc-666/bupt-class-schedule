import { describe, it, expect, beforeEach } from 'vitest';
import { SyncService, parseAsiaShanghaiDeadlineMs } from '../src/assignments/syncService';
import { MockRepository } from './mockRepository';
import { UCloudClient, UCloudError, RawUCloudAssignment, UCloudFetchResult, UCloudSession } from '../src/ucloud/client';
import { PushPlusClient, PushPlusSendResult } from '../src/notifications/pushplus';
import { encryptText, sha256Hex } from '../src/crypto/encryption';
import { UserRow } from '../src/storage/types';

describe('SyncService & Baseline & Diffing', () => {
  const masterKey = '0b60449fa3da9fc88b4cd40c0eb4cb3e480bd2a1d212f27acf20154dac31d542';
  let mockDb: MockRepository;
  let mockUCloud: UCloudClient;
  let mockPushPlus: PushPlusClient;
  let syncService: SyncService;
  let testUser: UserRow;
  let pushPlusSentList: Array<{ token: string; title: string; content: string }>;

  beforeEach(async () => {
    mockDb = new MockRepository();
    pushPlusSentList = [];

    // Mock PushPlus client
    mockPushPlus = {
      send: async (token: string, title: string, content: string): Promise<PushPlusSendResult> => {
        pushPlusSentList.push({ token, title, content });
        return { success: true, code: 200, message: 'ok' };
      },
      formatNewAssignmentMessage: PushPlusClient.prototype.formatNewAssignmentMessage,
      formatDeadlineChangedMessage: PushPlusClient.prototype.formatDeadlineChangedMessage,
      formatDueReminderMessage: PushPlusClient.prototype.formatDueReminderMessage,
      formatTestMessage: PushPlusClient.prototype.formatTestMessage,
    } as unknown as PushPlusClient;

    // Encrypted password for test user
    const enc = await encryptText('cloud_secret_pass', masterKey);
    const encPushPlus = await encryptText('pushplus_test_token', masterKey);

    const nowIso = new Date().toISOString();
    testUser = {
      id: 'user_123',
      account: 'test-student-1',
      api_token_hash: await sha256Hex('test_token'),
      ucloud_password_ciphertext: enc.ciphertext,
      ucloud_password_iv: enc.iv,
      binding_state: 'BOUND',
      snapshot_state: 'UNINITIALIZED',
      syncing_until: 0,
      created_at: nowIso,
      updated_at: nowIso,
      last_sync_at: null,
      last_sync_success_at: null,
      next_sync_at: null,
      last_sync_error_code: null,
    };
    mockDb.users.set(testUser.id, testUser);

    // Set up PushPlus settings (enabled by default in tests)
    await mockDb.upsertPushPlusSettings({
      user_id: testUser.id,
      enabled: 1,
      token_ciphertext: encPushPlus.ciphertext,
      token_iv: encPushPlus.iv,
      notify_new_assignment: 1,
      notify_deadline_change: 1,
      remind_24h: 1,
      remind_3h: 1,
      reminder_baseline_at: null,
      remind_24h_baseline_at: null,
      remind_3h_baseline_at: null,
      updated_at: nowIso,
    });
  });

  it('correctly parses Asia/Shanghai deadlines without timezone drift', () => {
    // 2026-09-20 23:59 Asia/Shanghai (UTC+8)
    const ms = parseAsiaShanghaiDeadlineMs('2026-09-20 23:59');
    expect(ms).not.toBeNull();
    const d = new Date(ms!);
    // In UTC, this should be 2026-09-20 15:59:00 UTC
    expect(d.toISOString()).toBe('2026-09-20T15:59:00.000Z');
  });

  it('BASELINE: UNINITIALIZED + 3 assignments writes 3 items, 0 NEW notifications sent', async () => {
    const rawAssignments: RawUCloudAssignment[] = [
      {
        assignmentId: 'assign_1',
        siteId: 'site_math',
        courseName: '高等数学',
        title: '课后习题 1',
        chapterName: '第一章',
        rawDeadline: '2026-10-01 23:59',
        assignmentStatus: 99,
        raw: {},
      },
      {
        assignmentId: 'assign_2',
        siteId: 'site_cs',
        courseName: '计算机系统',
        title: '实验 1',
        chapterName: '实验',
        rawDeadline: '2026-10-02 23:59',
        assignmentStatus: 99,
        raw: {},
      },
      {
        assignmentId: 'assign_3',
        siteId: 'site_eng',
        courseName: '大学英语',
        title: '作文 1',
        chapterName: null,
        rawDeadline: '2026-10-03 23:59',
        assignmentStatus: 99,
        raw: {},
      },
    ];

    mockUCloud = {
      login: async () => ({ accessToken: 'token_abc', userId: 'uid_123' }),
      fetchAll: async () => ({
        assignments: rawAssignments,
        successfulSiteIds: new Set(['site_math', 'site_cs', 'site_eng']),
        failedSiteIds: new Set<string>(),
        allCoursesSucceeded: true,
      }),
    } as unknown as UCloudClient;

    syncService = new SyncService(mockDb, mockUCloud, mockPushPlus, masterKey);

    const res = await syncService.syncUserAssignments(testUser, 'BIND', { isBaselineOverride: true });

    expect(res.status).toBe('SUCCESS');
    expect(res.assignmentCount).toBe(3);
    expect(res.newCount).toBe(3);

    // Verify written to DB
    const active = await mockDb.getActiveAssignments(testUser.id);
    expect(active.length).toBe(3);

    // Crucial rule: ZERO PushPlus notifications on baseline!
    expect(pushPlusSentList.length).toBe(0);

    // User snapshot should now be INITIALIZED
    const updatedUser = await mockDb.getUserById(testUser.id);
    expect(updatedUser?.snapshot_state).toBe('INITIALIZED');
  });

  it('syncs tasks without a notification sender', async () => {
    mockDb.getPushPlusSettings = async () => {
      throw new Error('Notification settings must not be read without a sender');
    };
    mockUCloud = {
      login: async () => ({ accessToken: 'session', userId: 'fake-user' }),
      fetchAll: async () => ({
        assignments: [{
          assignmentId: 'task-1', siteId: 'site-1', courseName: '测试课程',
          title: '作业', chapterName: null, rawDeadline: null,
          assignmentStatus: 99, raw: {},
        }],
        successfulSiteIds: new Set(['site-1']),
        failedSiteIds: new Set<string>(),
        allCoursesSucceeded: true,
      }),
    } as unknown as UCloudClient;
    const service = new SyncService(mockDb, mockUCloud, null, masterKey);
    const result = await service.syncUserAssignments(testUser, 'SCHEDULED');
    expect(result.status).toBe('SUCCESS');
    expect((await mockDb.getActiveAssignments(testUser.id)).map(item => item.assignment_id)).toEqual(['task-1']);
    expect(mockDb.notificationDeliveries.size).toBe(0);
  });

  it('does not expose credentials from a failing assignment source', async () => {
    mockUCloud = {
      login: async () => { throw new UCloudError('INVALID_CREDENTIALS', 'password=do-not-leak'); },
      fetchAll: async () => { throw new Error('unreachable'); },
    } as unknown as UCloudClient;
    const service = new SyncService(mockDb, mockUCloud, null, masterKey);
    const result = await service.syncUserAssignments(testUser, 'SCHEDULED');
    expect(result.status).toBe('FAILED');
    expect(result.errorCode).toBe('INVALID_CREDENTIALS');
    expect(result.errorMessage).not.toContain('do-not-leak');
    expect(mockDb.syncRuns[0].error_code).toBe('INVALID_CREDENTIALS');
  });

  it('SECOND SYNC: old A, B, new A, B, C -> C is detected as NEW and triggers PushPlus', async () => {
    // 1. Setup baseline with A and B
    const initialList: RawUCloudAssignment[] = [
      {
        assignmentId: 'A',
        siteId: 'site_1',
        courseName: '计算机网络',
        title: '作业A',
        chapterName: null,
        rawDeadline: '2026-10-01 23:59',
        assignmentStatus: 99,
        raw: {},
      },
      {
        assignmentId: 'B',
        siteId: 'site_1',
        courseName: '计算机网络',
        title: '作业B',
        chapterName: null,
        rawDeadline: '2026-10-02 23:59',
        assignmentStatus: 99,
        raw: {},
      },
    ];

    mockUCloud = {
      login: async () => ({ accessToken: 't', userId: 'u' }),
      fetchAll: async () => ({
        assignments: initialList,
        successfulSiteIds: new Set(['site_1']),
        failedSiteIds: new Set<string>(),
        allCoursesSucceeded: true,
      }),
    } as unknown as UCloudClient;

    syncService = new SyncService(mockDb, mockUCloud, mockPushPlus, masterKey);
    await syncService.syncUserAssignments(testUser, 'BIND', { isBaselineOverride: true });
    expect(pushPlusSentList.length).toBe(0);

    // 2. Second sync: A, B, C
    const secondList: RawUCloudAssignment[] = [
      ...initialList,
      {
        assignmentId: 'C',
        siteId: 'site_1',
        courseName: '计算机网络',
        title: '作业C',
        chapterName: null,
        rawDeadline: '2026-10-05 23:59',
        assignmentStatus: 99,
        raw: {},
      },
    ];

    mockUCloud = {
      login: async () => ({ accessToken: 't', userId: 'u' }),
      fetchAll: async () => ({
        assignments: secondList,
        successfulSiteIds: new Set(['site_1']),
        failedSiteIds: new Set<string>(),
        allCoursesSucceeded: true,
      }),
    } as unknown as UCloudClient;

    syncService = new SyncService(mockDb, mockUCloud, mockPushPlus, masterKey);
    const updatedUser = (await mockDb.getUserById(testUser.id))!;
    const res = await syncService.syncUserAssignments(updatedUser, 'SCHEDULED');

    expect(res.status).toBe('SUCCESS');
    expect(res.newCount).toBe(1);
    expect(res.assignmentCount).toBe(3);

    // Check PushPlus notification sent for C
    expect(pushPlusSentList.length).toBe(1);
    expect(pushPlusSentList[0].title).toBe('【北邮课表】发现新作业');
    expect(pushPlusSentList[0].content).toContain('作业C');
    expect(pushPlusSentList[0].content).toContain('计算机网络');
  });

  it('DDL CHANGE: assignment deadline updated triggers DEADLINE_CHANGED notification', async () => {
    // 1. Baseline with assignment A
    const initialList: RawUCloudAssignment[] = [
      {
        assignmentId: 'A',
        siteId: 'site_1',
        courseName: '算法设计',
        title: '大作业',
        chapterName: null,
        rawDeadline: '2026-10-10 12:00',
        assignmentStatus: 99,
        raw: {},
      },
    ];

    mockUCloud = {
      login: async () => ({ accessToken: 't', userId: 'u' }),
      fetchAll: async () => ({
        assignments: initialList,
        successfulSiteIds: new Set(['site_1']),
        failedSiteIds: new Set<string>(),
        allCoursesSucceeded: true,
      }),
    } as unknown as UCloudClient;

    syncService = new SyncService(mockDb, mockUCloud, mockPushPlus, masterKey);
    await syncService.syncUserAssignments(testUser, 'BIND', { isBaselineOverride: true });
    pushPlusSentList = [];

    // 2. Deadline changed to 2026-10-15 23:59
    const updatedList: RawUCloudAssignment[] = [
      {
        ...initialList[0],
        rawDeadline: '2026-10-15 23:59',
      },
    ];

    mockUCloud = {
      login: async () => ({ accessToken: 't', userId: 'u' }),
      fetchAll: async () => ({
        assignments: updatedList,
        successfulSiteIds: new Set(['site_1']),
        failedSiteIds: new Set<string>(),
        allCoursesSucceeded: true,
      }),
    } as unknown as UCloudClient;

    syncService = new SyncService(mockDb, mockUCloud, mockPushPlus, masterKey);
    const updatedUser = (await mockDb.getUserById(testUser.id))!;
    const res = await syncService.syncUserAssignments(updatedUser, 'SCHEDULED');

    expect(res.status).toBe('SUCCESS');
    expect(res.updatedCount).toBe(1);

    expect(pushPlusSentList.length).toBe(1);
    expect(pushPlusSentList[0].title).toBe('【北邮课表】作业截止时间已更新');
    expect(pushPlusSentList[0].content).toContain('原截止时间：2026-10-10 12:00');
    expect(pushPlusSentList[0].content).toContain('新截止时间：2026-10-15 23:59');
  });

  it('REMOVED: old A, B, new A -> B marked REMOVED and NO "submitted" notification sent', async () => {
    // 1. Baseline with A and B
    const initialList: RawUCloudAssignment[] = [
      {
        assignmentId: 'A',
        siteId: 'site_1',
        courseName: 'C++',
        title: 'Project 1',
        chapterName: null,
        rawDeadline: '2026-10-01 23:59',
        assignmentStatus: 99,
        raw: {},
      },
      {
        assignmentId: 'B',
        siteId: 'site_1',
        courseName: 'C++',
        title: 'Project 2',
        chapterName: null,
        rawDeadline: '2026-10-02 23:59',
        assignmentStatus: 99,
        raw: {},
      },
    ];

    mockUCloud = {
      login: async () => ({ accessToken: 't', userId: 'u' }),
      fetchAll: async () => ({
        assignments: initialList,
        successfulSiteIds: new Set(['site_1']),
        failedSiteIds: new Set<string>(),
        allCoursesSucceeded: true,
      }),
    } as unknown as UCloudClient;

    syncService = new SyncService(mockDb, mockUCloud, mockPushPlus, masterKey);
    await syncService.syncUserAssignments(testUser, 'BIND', { isBaselineOverride: true });
    pushPlusSentList = [];

    // 2. New snapshot only has A
    const newList: RawUCloudAssignment[] = [initialList[0]];

    mockUCloud = {
      login: async () => ({ accessToken: 't', userId: 'u' }),
      fetchAll: async () => ({
        assignments: newList,
        successfulSiteIds: new Set(['site_1']),
        failedSiteIds: new Set<string>(),
        allCoursesSucceeded: true,
      }),
    } as unknown as UCloudClient;

    syncService = new SyncService(mockDb, mockUCloud, mockPushPlus, masterKey);
    const updatedUser = (await mockDb.getUserById(testUser.id))!;
    const res = await syncService.syncUserAssignments(updatedUser, 'SCHEDULED');

    expect(res.status).toBe('SUCCESS');
    expect(res.removedCount).toBe(1);

    // Active assignments now only has A
    const active = await mockDb.getActiveAssignments(testUser.id);
    expect(active.length).toBe(1);
    expect(active[0].assignment_id).toBe('A');

    // B is marked REMOVED in db
    const all = await mockDb.getAllAssignments(testUser.id);
    const b = all.find((x) => x.assignment_id === 'B');
    expect(b?.state).toBe('REMOVED');
    expect(b?.removed_at).not.toBeNull();

    // Crucial: NO PushPlus notification sent for REMOVED!
    expect(pushPlusSentList.length).toBe(0);
  });

  it('PARTIAL SYNC: failed course old assignments are preserved and not removed', async () => {
    // 1. Initial with Course 1 (assign_c1) and Course 2 (assign_c2)
    const initialList: RawUCloudAssignment[] = [
      {
        assignmentId: 'assign_c1',
        siteId: 'site_course_1',
        courseName: '课程一',
        title: '作业一',
        chapterName: null,
        rawDeadline: '2026-10-01 23:59',
        assignmentStatus: 99,
        raw: {},
      },
      {
        assignmentId: 'assign_c2',
        siteId: 'site_course_2',
        courseName: '课程二',
        title: '作业二',
        chapterName: null,
        rawDeadline: '2026-10-02 23:59',
        assignmentStatus: 99,
        raw: {},
      },
    ];

    mockUCloud = {
      login: async () => ({ accessToken: 't', userId: 'u' }),
      fetchAll: async () => ({
        assignments: initialList,
        successfulSiteIds: new Set(['site_course_1', 'site_course_2']),
        failedSiteIds: new Set<string>(),
        allCoursesSucceeded: true,
      }),
    } as unknown as UCloudClient;

    syncService = new SyncService(mockDb, mockUCloud, mockPushPlus, masterKey);
    await syncService.syncUserAssignments(testUser, 'BIND', { isBaselineOverride: true });

    // 2. Next sync: site_course_2 fails! Only site_course_1 succeeded with assign_c1
    mockUCloud = {
      login: async () => ({ accessToken: 't', userId: 'u' }),
      fetchAll: async () => ({
        assignments: [initialList[0]], // only course 1 returned
        successfulSiteIds: new Set(['site_course_1']),
        failedSiteIds: new Set(['site_course_2']),
        allCoursesSucceeded: false,
      }),
    } as unknown as UCloudClient;

    syncService = new SyncService(mockDb, mockUCloud, mockPushPlus, masterKey);
    const updatedUser = (await mockDb.getUserById(testUser.id))!;
    const res = await syncService.syncUserAssignments(updatedUser, 'SCHEDULED');

    expect(res.status).toBe('PARTIAL');
    // assign_c2 should NOT be removed!
    const active = await mockDb.getActiveAssignments(testUser.id);
    expect(active.length).toBe(2);
    expect(active.map((a) => a.assignment_id).sort()).toEqual(['assign_c1', 'assign_c2']);
  });

  it('ALL COURSES FAIL: aborts without destructive update', async () => {
    // 1. Initial assignment A
    const initialList: RawUCloudAssignment[] = [
      {
        assignmentId: 'A',
        siteId: 'site_1',
        courseName: '课程一',
        title: '作业A',
        chapterName: null,
        rawDeadline: '2026-10-01 23:59',
        assignmentStatus: 99,
        raw: {},
      },
    ];

    mockUCloud = {
      login: async () => ({ accessToken: 't', userId: 'u' }),
      fetchAll: async () => ({
        assignments: initialList,
        successfulSiteIds: new Set(['site_1']),
        failedSiteIds: new Set<string>(),
        allCoursesSucceeded: true,
      }),
    } as unknown as UCloudClient;

    syncService = new SyncService(mockDb, mockUCloud, mockPushPlus, masterKey);
    await syncService.syncUserAssignments(testUser, 'BIND', { isBaselineOverride: true });

    // 2. All fail in UCloud fetchAll
    mockUCloud = {
      login: async () => ({ accessToken: 't', userId: 'u' }),
      fetchAll: async () => {
        throw new Error('网络断开，无法拉取课程');
      },
    } as unknown as UCloudClient;

    syncService = new SyncService(mockDb, mockUCloud, mockPushPlus, masterKey);
    const updatedUser = (await mockDb.getUserById(testUser.id))!;
    const res = await syncService.syncUserAssignments(updatedUser, 'SCHEDULED');

    expect(res.status).toBe('FAILED');
    // Old assignment A must still be intact
    const active = await mockDb.getActiveAssignments(testUser.id);
    expect(active.length).toBe(1);
    expect(active[0].assignment_id).toBe('A');
  });

  it('DEDUPE: running identical sync does not duplicate notifications', async () => {
    const list: RawUCloudAssignment[] = [
      {
        assignmentId: 'X',
        siteId: 'site_1',
        courseName: '网络安全',
        title: '实验一',
        chapterName: null,
        rawDeadline: '2026-10-20 23:59',
        assignmentStatus: 99,
        raw: {},
      },
    ];

    let currentMockList: RawUCloudAssignment[] = [];
    mockUCloud = {
      login: async () => ({ accessToken: 't', userId: 'u' }),
      fetchAll: async () => ({
        assignments: currentMockList,
        successfulSiteIds: new Set(['site_1']),
        failedSiteIds: new Set<string>(),
        allCoursesSucceeded: true,
      }),
    } as unknown as UCloudClient;

    syncService = new SyncService(mockDb, mockUCloud, mockPushPlus, masterKey);
    // Baseline with empty
    await syncService.syncUserAssignments(testUser, 'BIND', { isBaselineOverride: true });
    pushPlusSentList = [];

    // Sync 1: X added
    currentMockList = list;
    const user1 = (await mockDb.getUserById(testUser.id))!;
    await syncService.syncUserAssignments(user1, 'SCHEDULED');
    expect(pushPlusSentList.length).toBe(1);

    // Sync 2: X unchanged
    const user2 = (await mockDb.getUserById(testUser.id))!;
    await syncService.syncUserAssignments(user2, 'SCHEDULED');
    expect(pushPlusSentList.length).toBe(1); // Still 1, not duplicated!
  });

  it('PUSHPLUS FAILURE: notification failure does not break assignment persistence', async () => {
    const failingPushPlus = {
      send: async (): Promise<PushPlusSendResult> => {
        return { success: false, code: 500, message: 'PushPlus service temporary unavailable' };
      },
      formatNewAssignmentMessage: PushPlusClient.prototype.formatNewAssignmentMessage,
      formatDeadlineChangedMessage: PushPlusClient.prototype.formatDeadlineChangedMessage,
      formatDueReminderMessage: PushPlusClient.prototype.formatDueReminderMessage,
      formatTestMessage: PushPlusClient.prototype.formatTestMessage,
    } as unknown as PushPlusClient;

    // Baseline with empty
    mockUCloud = {
      login: async () => ({ accessToken: 't', userId: 'u' }),
      fetchAll: async () => ({
        assignments: [],
        successfulSiteIds: new Set<string>(),
        failedSiteIds: new Set<string>(),
        allCoursesSucceeded: true,
      }),
    } as unknown as UCloudClient;

    syncService = new SyncService(mockDb, mockUCloud, failingPushPlus, masterKey);
    await syncService.syncUserAssignments(testUser, 'BIND', { isBaselineOverride: true });

    // Sync with new assignment
    mockUCloud = {
      login: async () => ({ accessToken: 't', userId: 'u' }),
      fetchAll: async () => ({
        assignments: [
          {
            assignmentId: 'Y',
            siteId: 'site_1',
            courseName: '课程Y',
            title: '作业Y',
            chapterName: null,
            rawDeadline: '2026-10-30 23:59',
            assignmentStatus: 99,
            raw: {},
          },
        ],
        successfulSiteIds: new Set(['site_1']),
        failedSiteIds: new Set<string>(),
        allCoursesSucceeded: true,
      }),
    } as unknown as UCloudClient;

    syncService = new SyncService(mockDb, mockUCloud, failingPushPlus, masterKey);
    const user = (await mockDb.getUserById(testUser.id))!;
    const res = await syncService.syncUserAssignments(user, 'SCHEDULED');

    // Sync should still SUCCEED and assignment Y must be saved to DB!
    expect(res.status).toBe('SUCCESS');
    const active = await mockDb.getActiveAssignments(testUser.id);
    expect(active.length).toBe(1);
    expect(active[0].assignment_id).toBe('Y');
  });

  it('COURSE CLEANUP: when allCoursesSucceeded is true, assignments from completely vanished course are marked REMOVED', async () => {
    const assignOld: RawUCloudAssignment = {
      assignmentId: 'assign_old',
      siteId: 'site_old_course',
      courseName: '已结束课程',
      title: '旧大作业',
      chapterName: null,
      rawDeadline: '2026-10-01 23:59',
      assignmentStatus: 99,
      raw: {},
    };
    const assignStay: RawUCloudAssignment = {
      assignmentId: 'assign_stay',
      siteId: 'site_stay',
      courseName: '正常课程',
      title: '在学作业',
      chapterName: null,
      rawDeadline: '2026-10-10 23:59',
      assignmentStatus: 99,
      raw: {},
    };

    mockUCloud = {
      login: async () => ({ accessToken: 't', userId: 'u' }),
      fetchAll: async () => ({
        assignments: [assignOld, assignStay],
        successfulSiteIds: new Set(['site_old_course', 'site_stay']),
        failedSiteIds: new Set<string>(),
        allCoursesSucceeded: true,
      }),
    } as unknown as UCloudClient;

    syncService = new SyncService(mockDb, mockUCloud, mockPushPlus, masterKey);
    await syncService.syncUserAssignments(testUser, 'BIND', { isBaselineOverride: true });
    expect((await mockDb.getActiveAssignments(testUser.id)).length).toBe(2);

    // Second sync: course_old completely vanished from course list (successfulSiteIds only has site_stay, allCoursesSucceeded is true)
    mockUCloud = {
      login: async () => ({ accessToken: 't', userId: 'u' }),
      fetchAll: async () => ({
        assignments: [assignStay],
        successfulSiteIds: new Set(['site_stay']),
        failedSiteIds: new Set<string>(),
        allCoursesSucceeded: true,
      }),
    } as unknown as UCloudClient;

    syncService = new SyncService(mockDb, mockUCloud, mockPushPlus, masterKey);
    const user = (await mockDb.getUserById(testUser.id))!;
    const res = await syncService.syncUserAssignments(user, 'SCHEDULED');

    expect(res.status).toBe('SUCCESS');
    expect(res.removedCount).toBe(1);
    const active = await mockDb.getActiveAssignments(testUser.id);
    expect(active.length).toBe(1);
    expect(active[0].assignment_id).toBe('assign_stay');

    const all = await mockDb.getAllAssignments(testUser.id);
    const old = all.find((a) => a.assignment_id === 'assign_old');
    expect(old?.state).toBe('REMOVED');
  });

  it('PARTIAL SAFE RETENTION: when allCoursesSucceeded is false, assignments from failed courses are preserved ACTIVE', async () => {
    const assignCourseA: RawUCloudAssignment = {
      assignmentId: 'assign_a',
      siteId: 'site_a',
      courseName: '课程A',
      title: '作业A',
      chapterName: null,
      rawDeadline: '2026-10-01 23:59',
      assignmentStatus: 99,
      raw: {},
    };
    const assignCourseB: RawUCloudAssignment = {
      assignmentId: 'assign_b',
      siteId: 'site_b',
      courseName: '课程B',
      title: '作业B',
      chapterName: null,
      rawDeadline: '2026-10-02 23:59',
      assignmentStatus: 99,
      raw: {},
    };

    mockUCloud = {
      login: async () => ({ accessToken: 't', userId: 'u' }),
      fetchAll: async () => ({
        assignments: [assignCourseA, assignCourseB],
        successfulSiteIds: new Set(['site_a', 'site_b']),
        failedSiteIds: new Set<string>(),
        allCoursesSucceeded: true,
      }),
    } as unknown as UCloudClient;

    syncService = new SyncService(mockDb, mockUCloud, mockPushPlus, masterKey);
    await syncService.syncUserAssignments(testUser, 'BIND', { isBaselineOverride: true });

    // Partial sync: course B failed, course A succeeded
    mockUCloud = {
      login: async () => ({ accessToken: 't', userId: 'u' }),
      fetchAll: async () => ({
        assignments: [assignCourseA],
        successfulSiteIds: new Set(['site_a']),
        failedSiteIds: new Set(['site_b']),
        allCoursesSucceeded: false,
      }),
    } as unknown as UCloudClient;

    syncService = new SyncService(mockDb, mockUCloud, mockPushPlus, masterKey);
    const user = (await mockDb.getUserById(testUser.id))!;
    const res = await syncService.syncUserAssignments(user, 'SCHEDULED');

    expect(res.status).toBe('PARTIAL');
    expect(res.removedCount).toBe(0);
    // Both assign_a and assign_b must remain ACTIVE
    const active = await mockDb.getActiveAssignments(testUser.id);
    expect(active.length).toBe(2);
    expect(active.map((a) => a.assignment_id).sort()).toEqual(['assign_a', 'assign_b']);
  });

  it('PARTIAL BASELINE: first sync with partial failure keeps snapshot_state UNINITIALIZED, second sync recovering does NOT send NEW notification', async () => {
    // First sync (baseline) has site_1 success, site_2 failure
    const assign1: RawUCloudAssignment = {
      assignmentId: 'assign_1',
      siteId: 'site_1',
      courseName: '课程1',
      title: '作业1',
      chapterName: null,
      rawDeadline: '2026-10-01 23:59',
      assignmentStatus: 99,
      raw: {},
    };
    const assign2: RawUCloudAssignment = {
      assignmentId: 'assign_2',
      siteId: 'site_2',
      courseName: '课程2',
      title: '作业2',
      chapterName: null,
      rawDeadline: '2026-10-02 23:59',
      assignmentStatus: 99,
      raw: {},
    };

    mockUCloud = {
      login: async () => ({ accessToken: 't', userId: 'u' }),
      fetchAll: async () => ({
        assignments: [assign1],
        successfulSiteIds: new Set(['site_1']),
        failedSiteIds: new Set(['site_2']),
        allCoursesSucceeded: false,
      }),
    } as unknown as UCloudClient;

    syncService = new SyncService(mockDb, mockUCloud, mockPushPlus, masterKey);
    await syncService.syncUserAssignments(testUser, 'BIND');

    // snapshot_state must remain UNINITIALIZED because it was partial
    let user = (await mockDb.getUserById(testUser.id))!;
    expect(user.snapshot_state).toBe('UNINITIALIZED');
    expect(pushPlusSentList.length).toBe(0);

    // Second sync: site_2 recovers and succeeds!
    mockUCloud = {
      login: async () => ({ accessToken: 't', userId: 'u' }),
      fetchAll: async () => ({
        assignments: [assign1, assign2],
        successfulSiteIds: new Set(['site_1', 'site_2']),
        failedSiteIds: new Set<string>(),
        allCoursesSucceeded: true,
      }),
    } as unknown as UCloudClient;

    syncService = new SyncService(mockDb, mockUCloud, mockPushPlus, masterKey);
    await syncService.syncUserAssignments(user, 'SCHEDULED');

    // Now all courses succeeded, snapshot_state becomes INITIALIZED
    user = (await mockDb.getUserById(testUser.id))!;
    expect(user.snapshot_state).toBe('INITIALIZED');
    // Crucial: assign2 was part of the initial baseline recovery, so 0 NEW notifications should be sent!
    expect(pushPlusSentList.length).toBe(0);
  });

  it('DUE REMINDER BASELINE: effectiveBaselineMs prevents retrospective notifications', async () => {
    const now = Date.now();
    const toCnFormat = (ts: number) => {
      const d = new Date(ts + 8 * 3600 * 1000);
      return d.toISOString().replace('T', ' ').substring(0, 16);
    };

    const deadline2h = toCnFormat(now + 2 * 3600 * 1000); // 2h left
    const deadline10h = toCnFormat(now + 10 * 3600 * 1000); // 10h left
    const deadline30h = toCnFormat(now + 30 * 3600 * 1000); // 30h left

    // Enable PushPlus with baseline = now
    const nowIso = new Date(now).toISOString();
    const encPushPlus = await encryptText('pushplus_test_token', masterKey);
    await mockDb.upsertPushPlusSettings({
      user_id: testUser.id,
      enabled: 1,
      token_ciphertext: encPushPlus.ciphertext,
      token_iv: encPushPlus.iv,
      notify_new_assignment: 0,
      notify_deadline_change: 0,
      remind_24h: 1,
      remind_3h: 1,
      reminder_baseline_at: nowIso,
      remind_24h_baseline_at: nowIso,
      remind_3h_baseline_at: nowIso,
      updated_at: nowIso,
    });

    const rawAssignments: RawUCloudAssignment[] = [
      {
        assignmentId: 'a_2h',
        siteId: 's',
        courseName: 'C1',
        title: 'T1',
        chapterName: null,
        rawDeadline: deadline2h,
        assignmentStatus: 99,
        raw: {},
      },
      {
        assignmentId: 'a_10h',
        siteId: 's',
        courseName: 'C2',
        title: 'T2',
        chapterName: null,
        rawDeadline: deadline10h,
        assignmentStatus: 99,
        raw: {},
      },
      {
        assignmentId: 'a_30h',
        siteId: 's',
        courseName: 'C3',
        title: 'T3',
        chapterName: null,
        rawDeadline: deadline30h,
        assignmentStatus: 99,
        raw: {},
      },
    ];

    mockUCloud = {
      login: async () => ({ accessToken: 't', userId: 'u' }),
      fetchAll: async () => ({
        assignments: rawAssignments,
        successfulSiteIds: new Set(['s']),
        failedSiteIds: new Set<string>(),
        allCoursesSucceeded: true,
      }),
    } as unknown as UCloudClient;

    // First do baseline
    syncService = new SyncService(mockDb, mockUCloud, mockPushPlus, masterKey);
    await syncService.syncUserAssignments(testUser, 'BIND', { isBaselineOverride: true });
    expect(pushPlusSentList.length).toBe(0);

    // Now run normal CRON at current time
    const user = (await mockDb.getUserById(testUser.id))!;
    await syncService.syncUserAssignments(user, 'SCHEDULED');

    // 2h left: baseline was established at deadline - 2h > deadline - 3h -> NO 3h notification!
    // 10h left: baseline was established at deadline - 10h > deadline - 24h -> NO 24h notification!
    // 30h left: 30h remaining > 24h -> not in window yet.
    // Total sent: 0 retrospective notifications!
    expect(pushPlusSentList.length).toBe(0);
  });

  it('DEFAULT POLICY: baseline suppresses history, while later NEW and 3h notifications still fire', async () => {
    const now = Date.now();
    const toCnFormat = (ts: number) => {
      const d = new Date(ts + 8 * 3600 * 1000);
      return d.toISOString().replace('T', ' ').substring(0, 16);
    };
    const assignment = (id: string, title: string, deadlineMs: number): RawUCloudAssignment => ({
      assignmentId: id,
      siteId: 'site_1',
      courseName: '默认策略课程',
      title,
      chapterName: null,
      rawDeadline: toCnFormat(deadlineMs),
      assignmentStatus: 99,
      raw: {},
    });

    const historicalInside3h = assignment('history_2h', '历史作业2h', now + 2 * 3600 * 1000);
    const historicalCrosses3h = assignment('history_5h', '历史作业5h', now + 5 * 3600 * 1000);
    const laterNewAssignment = assignment('new_6h', '后续新增作业6h', now + 6 * 3600 * 1000);
    let currentAssignments = [historicalInside3h, historicalCrosses3h];

    const encPushPlus = await encryptText('pushplus_test_token', masterKey);
    const baselineIso = new Date(now).toISOString();
    await mockDb.upsertPushPlusSettings({
      user_id: testUser.id,
      enabled: 1,
      token_ciphertext: encPushPlus.ciphertext,
      token_iv: encPushPlus.iv,
      notify_new_assignment: 1,
      notify_deadline_change: 0,
      remind_24h: 0,
      remind_3h: 1,
      reminder_baseline_at: baselineIso,
      remind_24h_baseline_at: null,
      remind_3h_baseline_at: baselineIso,
      updated_at: baselineIso,
    });

    mockUCloud = {
      login: async () => ({ accessToken: 't', userId: 'u' }),
      fetchAll: async () => ({
        assignments: currentAssignments,
        successfulSiteIds: new Set(['site_1']),
        failedSiteIds: new Set<string>(),
        allCoursesSucceeded: true,
      }),
    } as unknown as UCloudClient;
    syncService = new SyncService(mockDb, mockUCloud, mockPushPlus, masterKey);

    const originalNow = Date.now;
    try {
      Date.now = () => now;
      await syncService.syncUserAssignments(testUser, 'BIND', { isBaselineOverride: true });
      expect(pushPlusSentList).toHaveLength(0);

      const initializedUser = (await mockDb.getUserById(testUser.id))!;
      await syncService.syncUserAssignments(initializedUser, 'SCHEDULED');
      expect(pushPlusSentList).toHaveLength(0);

      Date.now = () => now + 2.5 * 3600 * 1000;
      currentAssignments = [...currentAssignments, laterNewAssignment];
      await syncService.syncUserAssignments(initializedUser, 'SCHEDULED');

      expect(pushPlusSentList).toHaveLength(2);
      expect(pushPlusSentList.some((message) => message.title === '【北邮课表】发现新作业' && message.content.includes('后续新增作业6h'))).toBe(true);
      expect(pushPlusSentList.some((message) => message.title.includes('3小时内') && message.content.includes('历史作业5h'))).toBe(true);
      expect(pushPlusSentList.some((message) => message.content.includes('历史作业2h'))).toBe(false);
      expect(pushPlusSentList.some((message) => message.title.includes('24小时内'))).toBe(false);

      Date.now = () => now + 3.5 * 3600 * 1000;
      await syncService.syncUserAssignments(initializedUser, 'SCHEDULED');

      expect(pushPlusSentList).toHaveLength(3);
      expect(pushPlusSentList.some((message) => message.title.includes('3小时内') && message.content.includes('后续新增作业6h'))).toBe(true);
    } finally {
      Date.now = originalNow;
    }
  });

  it('VERSION STABILITY: identical syncs do not bump updated_at and produce identical version', async () => {
    const rawAssignment: RawUCloudAssignment = {
      assignmentId: 'stable_assign',
      siteId: 'site_1',
      courseName: '高等数学',
      title: '微积分作业',
      chapterName: null,
      rawDeadline: '2026-10-20 23:59',
      assignmentStatus: 99,
      raw: {},
    };

    mockUCloud = {
      login: async () => ({ accessToken: 't', userId: 'u' }),
      fetchAll: async () => ({
        assignments: [rawAssignment],
        successfulSiteIds: new Set(['site_1']),
        failedSiteIds: new Set<string>(),
        allCoursesSucceeded: true,
      }),
    } as unknown as UCloudClient;

    syncService = new SyncService(mockDb, mockUCloud, mockPushPlus, masterKey);
    await syncService.syncUserAssignments(testUser, 'BIND', { isBaselineOverride: true });

    const active1 = await mockDb.getActiveAssignments(testUser.id);
    const initialUpdatedAt = active1[0].updated_at;

    // Simulate wait and run second identical sync
    const user = (await mockDb.getUserById(testUser.id))!;
    await syncService.syncUserAssignments(user, 'SCHEDULED');

    const active2 = await mockDb.getActiveAssignments(testUser.id);
    // updated_at MUST remain identical
    expect(active2[0].updated_at).toBe(initialUpdatedAt);
  });

  it('UCLOUD ERROR HANDLING: HTTP 200 with code 500 or success false in validateApiResponse throws error and active assignments are preserved', async () => {
    const { validateApiResponse, UCloudError } = await import('../src/ucloud/client');

    expect(() => {
      validateApiResponse({ code: 500, msg: '系统繁忙' });
    }).toThrow(UCloudError);

    expect(() => {
      validateApiResponse({ success: false, message: '操作失败' });
    }).toThrow(UCloudError);

    try {
      validateApiResponse({ code: 500, msg: 'password=super_secret_123 error' });
    } catch (e: any) {
      expect(e.message).not.toContain('super_secret_123');
      expect(e.message).toBe('云邮接口请求失败');
    }
  });

  it('validateApiResponse: only code 200 or "200" succeeds, all other non-200 codes fail', async () => {
    const { validateApiResponse, UCloudError } = await import('../src/ucloud/client');

    expect(() => validateApiResponse({ code: 200, data: 'ok' })).not.toThrow();
    expect(() => validateApiResponse({ code: '200', data: 'ok' })).not.toThrow();

    expect(() => validateApiResponse({ code: 500, msg: 'error' })).toThrow(UCloudError);
    expect(() => validateApiResponse({ code: '500', msg: 'error' })).toThrow(UCloudError);
    expect(() => validateApiResponse({ code: 'ERROR', msg: 'fail' })).toThrow(UCloudError);
    expect(() => validateApiResponse({ code: null, msg: 'null code' })).toThrow(UCloudError);
    expect(() => validateApiResponse({ code: 401, msg: 'session expired' })).toThrow(UCloudError);
  });

  it('fetchAll propagates NEEDS_REAUTH when REAUTH_REQUIRED or INVALID_CREDENTIALS thrown', async () => {
    const { UCloudError } = await import('../src/ucloud/client');
    const user = (await mockDb.getUserById(testUser.id))!;
    expect(user.binding_state).toBe('BOUND');

    mockUCloud = {
      login: async () => ({ accessToken: 't', userId: 'u' }),
      fetchAll: async () => {
        throw new UCloudError('REAUTH_REQUIRED', '会话过期需要重新登录');
      },
    } as unknown as UCloudClient;

    syncService = new SyncService(mockDb, mockUCloud, mockPushPlus, masterKey);
    const res = await syncService.syncUserAssignments(user, 'SCHEDULED');
    expect(res.status).toBe('FAILED');

    const updated = (await mockDb.getUserById(testUser.id))!;
    expect(updated.binding_state).toBe('NEEDS_REAUTH');
    expect(updated.last_sync_error_code).toBe('REAUTH_REQUIRED');
  });

  it('fetchAll does NOT set NEEDS_REAUTH on ordinary UPSTREAM_NETWORK_ERROR', async () => {
    const { UCloudError } = await import('../src/ucloud/client');
    const user = (await mockDb.getUserById(testUser.id))!;
    await mockDb.updateUserBindingState(user.id, 'BOUND', null, new Date().toISOString());

    mockUCloud = {
      login: async () => ({ accessToken: 't', userId: 'u' }),
      fetchAll: async () => {
        throw new UCloudError('UPSTREAM_NETWORK_ERROR', '网络连接超时');
      },
    } as unknown as UCloudClient;

    syncService = new SyncService(mockDb, mockUCloud, mockPushPlus, masterKey);
    const res = await syncService.syncUserAssignments(user, 'SCHEDULED');
    expect(res.status).toBe('FAILED');

    const updated = (await mockDb.getUserById(testUser.id))!;
    expect(updated.binding_state).toBe('BOUND');
    expect(updated.last_sync_error_code).toBe('UPSTREAM_NETWORK_ERROR');
  });

  it('PushPlus independent baselines: enable -> disable -> re-enable sets baseline to second enable; 2h left not notified; crossing threshold notifies', async () => {
    const now = Date.now();
    const toCnFormat = (ms: number) => {
      const d = new Date(ms + 8 * 3600 * 1000);
      return d.toISOString().replace('T', ' ').substring(0, 16);
    };
    const deadline2h = toCnFormat(now + 2 * 3600 * 1000);
    const deadline5h = toCnFormat(now + 5 * 3600 * 1000);

    const user = (await mockDb.getUserById(testUser.id))!;

    await mockDb.upsertAssignment({
      user_id: user.id,
      assignment_id: 'task_2h',
      site_id: 'site_1',
      course_name: '数学',
      title: '作业2h',
      chapter_name: null,
      raw_deadline: deadline2h,
      assignment_status: 99,
      state: 'ACTIVE',
      first_seen_at: new Date(now - 1000).toISOString(),
      last_seen_at: new Date(now - 1000).toISOString(),
      removed_at: null,
      updated_at: new Date(now - 1000).toISOString(),
      raw_hash: 'hash2h',
    });

    await mockDb.upsertAssignment({
      user_id: user.id,
      assignment_id: 'task_5h',
      site_id: 'site_1',
      course_name: '英语',
      title: '作业5h',
      chapter_name: null,
      raw_deadline: deadline5h,
      assignment_status: 99,
      state: 'ACTIVE',
      first_seen_at: new Date(now - 1000).toISOString(),
      last_seen_at: new Date(now - 1000).toISOString(),
      removed_at: null,
      updated_at: new Date(now - 1000).toISOString(),
      raw_hash: 'hash5h',
    });

    const encPushPlus = await encryptText('pushplus_test_token', masterKey);
    const reEnableTimeIso = new Date(now).toISOString();
    await mockDb.upsertPushPlusSettings({
      user_id: user.id,
      enabled: 1,
      token_ciphertext: encPushPlus.ciphertext,
      token_iv: encPushPlus.iv,
      notify_new_assignment: 0,
      notify_deadline_change: 0,
      remind_24h: 1,
      remind_3h: 1,
      reminder_baseline_at: reEnableTimeIso,
      remind_24h_baseline_at: reEnableTimeIso,
      remind_3h_baseline_at: reEnableTimeIso,
      updated_at: reEnableTimeIso,
    });

    const sentTypes: string[] = [];
    mockPushPlus = {
      send: async (_t: string, title: string, content: string) => {
        sentTypes.push(`${title}:${content}`);
        return { success: true };
      },
      formatNewAssignmentMessage: () => ({ title: 'NEW', content: 'new' }),
      formatDeadlineChangedMessage: () => ({ title: 'CHANGE', content: 'change' }),
      formatDueReminderMessage: (_c: string, title: string, _d: string, hours: number) => ({
        title: `REMIND_${hours}H`,
        content: title,
      }),
      formatTestMessage: () => ({ title: 'TEST', content: 'test' }),
    } as unknown as PushPlusClient;

    mockUCloud = {
      login: async () => ({ accessToken: 't', userId: 'u' }),
      fetchAll: async () => ({
        assignments: [
          {
            assignmentId: 'task_2h',
            siteId: 'site_1',
            courseName: '数学',
            title: '作业2h',
            chapterName: null,
            rawDeadline: deadline2h,
            assignmentStatus: 99,
            raw: {},
          },
          {
            assignmentId: 'task_5h',
            siteId: 'site_1',
            courseName: '英语',
            title: '作业5h',
            chapterName: null,
            rawDeadline: deadline5h,
            assignmentStatus: 99,
            raw: {},
          },
        ],
        successfulSiteIds: new Set(['site_1']),
        failedSiteIds: new Set<string>(),
        allCoursesSucceeded: true,
      }),
    } as unknown as UCloudClient;

    syncService = new SyncService(mockDb, mockUCloud, mockPushPlus, masterKey);

    await syncService.syncUserAssignments(user, 'SCHEDULED');
    expect(sentTypes.length).toBe(0);

    const originalNow = Date.now;
    try {
      Date.now = () => now + 2.5 * 3600 * 1000;
      await syncService.syncUserAssignments(user, 'SCHEDULED');
      expect(sentTypes.length).toBe(1);
      expect(sentTypes[0]).toContain('REMIND_3H');
      expect(sentTypes[0]).toContain('作业5h');
    } finally {
      Date.now = originalNow;
    }
  });

  it('UCloudClient.fetchAll rethrows REAUTH_REQUIRED from per-course assignment failure and SyncService sets NEEDS_REAUTH', async () => {
    const { UCloudClient, UCloudError } = await import('../src/ucloud/client');
    const client = new UCloudClient();

    client.fetchCourses = async () => [
      { siteId: 'course_1', siteName: '课程1', raw: {} },
      { siteId: 'course_2', siteName: '课程2', raw: {} },
    ];

    client.fetchAssignmentsForCourse = async (_session, course) => {
      if (course.siteId === 'course_1') {
        throw new UCloudError('REAUTH_REQUIRED', '认证凭证已过期');
      }
      return [];
    };
    client.fetchQuizzesForCourse = async () => [];

    await expect(client.fetchAll({ accessToken: 'tok', userId: 'u1' })).rejects.toThrow(UCloudError);
    try {
      await client.fetchAll({ accessToken: 'tok', userId: 'u1' });
    } catch (e: any) {
      expect(e.code).toBe('REAUTH_REQUIRED');
    }

    const user = (await mockDb.getUserById(testUser.id))!;
    await mockDb.updateUserBindingState(user.id, 'BOUND', null, new Date().toISOString());

    client.login = async () => ({ accessToken: 'tok', userId: 'u1' });
    syncService = new SyncService(mockDb, client, mockPushPlus, masterKey);
    const res = await syncService.syncUserAssignments(user, 'SCHEDULED');
    expect(res.status).toBe('FAILED');

    const updatedUser = (await mockDb.getUserById(testUser.id))!;
    expect(updatedUser.binding_state).toBe('NEEDS_REAUTH');
    expect(updatedUser.last_sync_error_code).toBe('REAUTH_REQUIRED');
  });

  it('UCloudClient.fetchAll treats ordinary single-course network error as partial failure, not NEEDS_REAUTH', async () => {
    const { UCloudClient, UCloudError } = await import('../src/ucloud/client');
    const client = new UCloudClient();

    client.fetchCourses = async () => [
      { siteId: 'course_1', siteName: '课程1', raw: {} },
      { siteId: 'course_2', siteName: '课程2', raw: {} },
    ];

    client.fetchAssignmentsForCourse = async (_session, course) => {
      if (course.siteId === 'course_1') {
        throw new UCloudError('UPSTREAM_NETWORK_ERROR', '单课程作业网络超时');
      }
      return [];
    };
    client.fetchQuizzesForCourse = async () => [];

    const fetchRes = await client.fetchAll({ accessToken: 'tok', userId: 'u1' });
    expect(fetchRes.successfulSiteIds.has('course_2')).toBe(true);
    expect(fetchRes.failedSiteIds.has('course_1')).toBe(true);
    expect(fetchRes.allCoursesSucceeded).toBe(false);

    const user = (await mockDb.getUserById(testUser.id))!;
    await mockDb.updateUserBindingState(user.id, 'BOUND', null, new Date().toISOString());

    client.login = async () => ({ accessToken: 'tok', userId: 'u1' });
    syncService = new SyncService(mockDb, client, mockPushPlus, masterKey);
    const res = await syncService.syncUserAssignments(user, 'SCHEDULED');
    expect(res.status).toBe('PARTIAL');

    const updatedUser = (await mockDb.getUserById(testUser.id))!;
    expect(updatedUser.binding_state).toBe('BOUND');
  });
});
