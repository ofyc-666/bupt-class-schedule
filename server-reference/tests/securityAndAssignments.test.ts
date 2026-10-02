import { afterEach, describe, expect, it, vi } from 'vitest';
import { assertAllowedUrl, safeFetch } from '../src/net/safeFetch';
import { parseTicket, UCloudClient } from '../src/ucloud/client';

afterEach(() => vi.unstubAllGlobals());

describe('HTTPS and assignment ingestion', () => {
  it('rejects unsafe CAS redirects and outbound hosts', () => {
    expect(parseTicket('http://ucloud.bupt.edu.cn/?ticket=bad')).toBeNull();
    expect(parseTicket('https://ucloud.bupt.edu.cn:8443/?ticket=bad')).toBeNull();
    expect(parseTicket('https://evil.example/?ticket=bad')).toBeNull();
    expect(parseTicket('https://ucloud.bupt.edu.cn/?ticket=good')).toBe('good');
    expect(() => assertAllowedUrl('http://apiucloud.bupt.edu.cn/')).toThrow();
    expect(() => assertAllowedUrl('https://evil.example/')).toThrow();
    expect(() => assertAllowedUrl('https://apiucloud.bupt.edu.cn:8443/')).toThrow();
    expect(() => assertAllowedUrl('https://name:secret@apiucloud.bupt.edu.cn/')).toThrow();
  });

  it('keeps redirects manual, applies timeout, and does not echo passwords in errors', async () => {
    const fetchMock = vi.fn(async () => new Response('', { status: 200 }));
    vi.stubGlobal('fetch', fetchMock);
    await safeFetch('https://apiucloud.bupt.edu.cn/test', { timeoutMs: 1000 });
    expect(fetchMock.mock.calls).toHaveLength(1);
    expect((fetchMock.mock.calls[0] as unknown as [string, RequestInit])[1].redirect).toBe('manual');

    vi.stubGlobal('fetch', vi.fn(async () => { throw new Error('password=secret-value'); }));
    await expect(new UCloudClient().login('student', 'secret-value'))
      .rejects.toMatchObject({ code: 'UPSTREAM_NETWORK_ERROR', message: '无法连接统一认证服务' });
  });

  it('reads assignment pages and accepts only assignmentStatus 99', async () => {
    const pages: number[] = [];
    vi.stubGlobal('fetch', vi.fn(async (_url: string, init: RequestInit) => {
      const request = JSON.parse(String(init.body));
      pages.push(request.current);
      expect((init.headers as Record<string, string>)['Blade-Auth']).toBe('session-token');
      const records = request.current === 1
        ? [{ id: 'pending', title: '待提交', assignmentStatus: 99, assignmentEndTime: '2026-10-01 12:00' },
           { id: 'done', title: '已提交', assignmentStatus: 1 }]
        : [{ id: 'pending-2', title: '第二页', assignmentStatus: '99', assignmentEndTime: '2026-10-02 12:00' }];
      return new Response(JSON.stringify({ code: 200, data: { records, pages: 2 } }), { status: 200 });
    }));
    const result = await new UCloudClient().fetchAssignmentsForCourse(
      { accessToken: 'session-token', userId: 'fake-user' },
      { siteId: 'fake-site', siteName: '测试课程', raw: {} },
    );
    expect(pages).toEqual([1, 2]);
    expect(result.map(item => item.assignmentId)).toEqual(['pending', 'pending-2']);
  });
});
