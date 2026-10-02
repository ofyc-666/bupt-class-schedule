import { safeFetch } from '../net/safeFetch';

export interface UCloudSession {
  accessToken: string;
  userId: string;
}

export interface UCloudCourseRecord {
  siteId: string;
  siteName: string;
  raw: Record<string, unknown>;
}

export interface RawUCloudAssignment {
  taskType?: 'ASSIGNMENT' | 'QUIZ';
  assignmentId: string;
  siteId: string;
  courseName: string;
  title: string;
  chapterName: string | null;
  rawDeadline: string | null;
  assignmentStatus: number;
  raw: Record<string, unknown>;
}

export interface UCloudFetchResult {
  assignments: RawUCloudAssignment[];
  successfulSiteIds: Set<string>;
  successfulAssignmentSiteIds?: Set<string>;
  successfulQuizSiteIds?: Set<string>;
  failedSiteIds: Set<string>;
  allCoursesSucceeded: boolean;
}

export class UCloudError extends Error {
  code: string;
  constructor(code: string, message: string) {
    super(message);
    this.name = 'UCloudError';
    this.code = code;
  }
}

export function parseExecution(html: string): string | null {
  const match = html.match(/<input[^>]*name=["']execution["'][^>]*value=["']([^"']+)["']/i)
    || html.match(/<input[^>]*value=["']([^"']+)["'][^>]*name=["']execution["']/i);
  return match ? match[1] : null;
}

export function parseTicket(location: string): string | null {
  try {
    const url = new URL(location, 'https://ucloud.bupt.edu.cn');
    if (url.protocol !== 'https:' || url.hostname.toLowerCase() !== 'ucloud.bupt.edu.cn' ||
        (url.port && url.port !== '443') || url.username || url.password) {
      return null;
    }
    return url.searchParams.get('ticket');
  } catch {
    return null;
  }
}


export function sanitizeErrorMessage(msg: string): string {
  let sanitized = msg;
  const sensitivePatterns = [
    /(?:bearer\s+)[a-zA-Z0-9_.-]+/gi,
    /(?:token\s*[:=]\s*)[a-zA-Z0-9_.-]+/gi,
    /(?:ticket\s*[:=]\s*)[a-zA-Z0-9_.-]+/gi,
    /(?:password\s*[:=]\s*)[^&\s]+/gi,
    /(?:cookie\s*[:=]\s*)[^;\s]+/gi,
  ];
  for (const p of sensitivePatterns) {
    sanitized = sanitized.replace(p, '[PROTECTED]');
  }
  return sanitized.slice(0, 100);
}

export function validateApiResponse(root: unknown, actionName = '接口'): Record<string, unknown> {
  if (!root || typeof root !== 'object') {
    throw new UCloudError('INVALID_RESPONSE', `云邮${actionName}数据格式异常`);
  }
  const obj = root as Record<string, unknown>;

  if ('code' in obj) {
    const rawCode = obj.code;
    const is200 = rawCode === 200 || rawCode === '200';
    if (!is200) {
      const codeNum = typeof rawCode === 'number' ? rawCode : Number(rawCode);
      const rawMsg = String(obj.msg || obj.message || `业务状态码异常 (${rawCode})`);
      const lowerMsg = rawMsg.toLowerCase();
      const lowerCode = String(rawCode).toLowerCase();
      if (
        codeNum === 401 || codeNum === 403 ||
        lowerCode === '401' ||
        lowerCode.includes('auth') ||
        lowerMsg.includes('登录') ||
        lowerMsg.includes('认证') ||
        lowerMsg.includes('未授权') ||
        lowerMsg.includes('token') ||
        lowerMsg.includes('session')
      ) {
        throw new UCloudError('REAUTH_REQUIRED', `云邮${actionName}需要重新登录`);
      }
      throw new UCloudError('INVALID_RESPONSE', `云邮${actionName}请求失败`);
    }
  }

  if ('success' in obj && obj.success === false) {
    throw new UCloudError('INVALID_RESPONSE', `云邮${actionName}请求失败`);
  }

  return obj;
}

export class UCloudClient {
  private static MAX_PAGES = 20;
  private static CAS_LOGIN_URL = 'https://auth.bupt.edu.cn/authserver/login?service=https%3A%2F%2Fucloud.bupt.edu.cn';
  private static API_ORIGIN = 'https://apiucloud.bupt.edu.cn';
  private static SERVICE_ORIGIN = 'https://ucloud.bupt.edu.cn';
  // OAuth client identifier embedded in the UCloud web client for login compatibility.
  // It is not a user credential or a project-specific secret.
  private static PORTAL_AUTHORIZATION = 'Basic  cG9ydGFsOnBvcnRhbF9zZWNyZXQ=';
  private static TENANT_ID = '000000';

  private apiHeaders(accessToken: string | null): Record<string, string> {
    const headers: Record<string, string> = {
      'Accept': 'application/json, text/plain, */*',
      'Authorization': UCloudClient.PORTAL_AUTHORIZATION,
      'Tenant-Id': UCloudClient.TENANT_ID,
      'Referer': `${UCloudClient.SERVICE_ORIGIN}/`,
    };
    if (accessToken) {
      headers['Blade-Auth'] = accessToken;
    }
    return headers;
  }

  async login(account: string, password: string): Promise<UCloudSession> {
    const cleanAccount = account.trim();
    if (!cleanAccount || !password) {
      throw new UCloudError('INVALID_CREDENTIALS', '学号或云邮密码为空');
    }

    // 1. GET CAS login page
    let loginPageRes: Response;
    try {
      loginPageRes = await safeFetch(UCloudClient.CAS_LOGIN_URL, {
        method: 'GET',
        headers: { 'Accept': 'text/html' },
      });
    } catch (err: unknown) {
      throw new UCloudError('UPSTREAM_NETWORK_ERROR', '无法连接统一认证服务');
    }

    if (!loginPageRes.ok) {
      throw new UCloudError('UPSTREAM_NETWORK_ERROR', `统一认证服务返回异常 (HTTP ${loginPageRes.status})`);
    }

    const html = await loginPageRes.text();
    const execution = parseExecution(html);
    if (!execution) {
      throw new UCloudError('UPSTREAM_PROTOCOL_CHANGED', '统一认证页面缺少 execution 参数');
    }

    // Extract cookies
    const cookieHeader = loginPageRes.headers.get('set-cookie') || '';
    const cookies = cookieHeader
      .split(/,(?=[^;]+=[^;]+)/)
      .map((c) => c.split(';')[0].trim())
      .filter((c) => c.length > 0)
      .join('; ');

    // Check if captcha is required on CAS login page
    if (html.includes('captcha') && html.includes('showCaptcha')) {
      // If captcha is actively required
      const captchaRequiredMatch = html.match(/id="cval"[^>]*style="display:\s*block"/i);
      if (captchaRequiredMatch) {
        throw new UCloudError('REAUTH_REQUIRED', '云邮登录需要验证码，请在网页端完成验证');
      }
    }

    // 2. POST CAS credentials
    const bodyParams = new URLSearchParams({
      username: cleanAccount,
      password: password,
      type: 'username_password',
      execution: execution,
      _eventId: 'submit',
    });

    let submitRes: Response;
    try {
      submitRes = await safeFetch(UCloudClient.CAS_LOGIN_URL, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/x-www-form-urlencoded',
          'Cookie': cookies,
          'Referer': UCloudClient.CAS_LOGIN_URL,
        },
        body: bodyParams.toString(),
        redirect: 'manual',
      });
    } catch (err: unknown) {
      throw new UCloudError('UPSTREAM_NETWORK_ERROR', '提交登录凭据失败');
    }

    const location = submitRes.headers.get('Location') || submitRes.headers.get('location') || '';
    const ticket = parseTicket(location);

    if (!ticket) {
      // Check response body for specific error message
      const errorHtml = await submitRes.text();
      if (errorHtml.includes('密码错误') || errorHtml.includes('用户名或密码有误') || errorHtml.includes('账号或密码错误')) {
        throw new UCloudError('INVALID_CREDENTIALS', '云邮账号或密码错误');
      }
      if (errorHtml.includes('验证码') || errorHtml.includes('图形验证码') || errorHtml.includes('动态码')) {
        throw new UCloudError('REAUTH_REQUIRED', '云邮需要验证码，请在网页端完成验证');
      }
      throw new UCloudError('REAUTH_REQUIRED', '统一认证未返回有效票据，请检查云邮密码或在网页端完成验证');
    }

    // 3. Exchange ticket for oauth token
    const tokenParams = new URLSearchParams({
      ticket: ticket,
      grant_type: 'third',
    });

    let tokenRes: Response;
    try {
      tokenRes = await safeFetch(`${UCloudClient.API_ORIGIN}/ykt-basics/oauth/token`, {
        method: 'POST',
        headers: {
          ...this.apiHeaders(null),
          'Content-Type': 'application/x-www-form-urlencoded',
        },
        body: tokenParams.toString(),
      });
    } catch (err: unknown) {
      throw new UCloudError('UPSTREAM_NETWORK_ERROR', '交换云邮令牌失败');
    }

    if (!tokenRes.ok) {
      throw new UCloudError('UPSTREAM_NETWORK_ERROR', `云邮令牌服务返回异常 (HTTP ${tokenRes.status})`);
    }

    let tokenJson: Record<string, unknown>;
    try {
      tokenJson = await tokenRes.json() as Record<string, unknown>;
    } catch {
      throw new UCloudError('INVALID_RESPONSE', '云邮令牌响应格式解析异常');
    }

    if (tokenJson.error) {
      throw new UCloudError('REAUTH_REQUIRED', '云邮认证失败，请重新登录');
    }

    if (typeof tokenJson.code === 'number' && tokenJson.code !== 200) {
      throw new UCloudError('REAUTH_REQUIRED', '云邮认证失败，请重新登录');
    }

    const accessToken = String(tokenJson.access_token || '').trim();
    const userId = String(tokenJson.user_id || tokenJson.userId || '').trim();

    if (!accessToken || !userId) {
      throw new UCloudError('REAUTH_REQUIRED', '云邮未返回有效访问令牌或用户标识');
    }

    return { accessToken, userId };
  }

  async fetchCourses(session: UCloudSession): Promise<UCloudCourseRecord[]> {
    const courses: UCloudCourseRecord[] = [];
    let currentPage = 1;
    let totalPages = 1;
    while (currentPage <= totalPages && currentPage <= UCloudClient.MAX_PAGES) {
      const query = new URLSearchParams({
        current: String(currentPage),
        size: '100',
        userId: session.userId,
        siteRoleCode: '2',
      });

      const url = `${UCloudClient.API_ORIGIN}/ykt-site/site/list/student/current?${query.toString()}`;
      const res = await safeFetch(url, {
        method: 'GET',
        headers: this.apiHeaders(session.accessToken),
      });

      if (!res.ok) {
        if (res.status === 401 || res.status === 403) {
          throw new UCloudError('REAUTH_REQUIRED', `拉取课程列表认证失效 (HTTP ${res.status})`);
        }
        throw new UCloudError('UPSTREAM_NETWORK_ERROR', `拉取课程列表失败 (HTTP ${res.status})`);
      }

      let rawRoot: unknown;
      try {
        rawRoot = await res.json();
      } catch {
        throw new UCloudError('INVALID_RESPONSE', '课程列表响应格式解析异常');
      }
      const root = validateApiResponse(rawRoot, '课程列表');
      const data = (root.data || {}) as Record<string, unknown>;
      const records = (Array.isArray(data.records) ? data.records : (Array.isArray(root.data) ? root.data : [])) as Record<string, unknown>[];

      for (const item of records) {
        const siteId = String(item.id || item.siteId || '').trim();
        const siteName = String(item.siteName || item.name || '').trim();
        if (siteId) {
          courses.push({ siteId, siteName, raw: item });
        }
      }

      const pages = Number(data.pages ?? 1);
      if (pages > UCloudClient.MAX_PAGES) {
        throw new UCloudError('INVALID_RESPONSE', '课程列表分页超过安全上限');
      }
      totalPages = Number.isFinite(pages) && pages > 0 ? pages : 1;
      if (currentPage >= totalPages) break;
      currentPage++;
    }

    // Deduplicate by siteId
    const seen = new Set<string>();
    const uniqueCourses: UCloudCourseRecord[] = [];
    for (const c of courses) {
      if (!seen.has(c.siteId)) {
        seen.add(c.siteId);
        uniqueCourses.push(c);
      }
    }
    return uniqueCourses;
  }

  async fetchAssignmentsForCourse(session: UCloudSession, course: UCloudCourseRecord): Promise<RawUCloudAssignment[]> {
    const assignments: RawUCloudAssignment[] = [];
    let currentPage = 1;
    let totalPages = 1;
    while (currentPage <= totalPages && currentPage <= UCloudClient.MAX_PAGES) {
      const payload = {
        siteId: course.siteId,
        userId: session.userId,
        keyword: '',
        current: currentPage,
        size: 100,
        studentAssignmentStatus: null,
        status: 0,
        sortColumn: '',
        sortType: null,
      };

      const res = await safeFetch(`${UCloudClient.API_ORIGIN}/ykt-site/work/student/list`, {
        method: 'POST',
        headers: {
          ...this.apiHeaders(session.accessToken),
          'Content-Type': 'application/json',
        },
        body: JSON.stringify(payload),
      });

      if (!res.ok) {
        if (res.status === 401 || res.status === 403) {
          throw new UCloudError('REAUTH_REQUIRED', `拉取课程作业认证失效 (HTTP ${res.status})`);
        }
        throw new UCloudError('UPSTREAM_NETWORK_ERROR', `拉取课程作业失败 (HTTP ${res.status})`);
      }

      let rawRoot: unknown;
      try {
        rawRoot = await res.json();
      } catch {
        throw new UCloudError('INVALID_RESPONSE', '课程作业响应格式解析异常');
      }
      const root = validateApiResponse(rawRoot, '课程作业');
      const data = (root.data || {}) as Record<string, unknown>;
      const records = (Array.isArray(data.records) ? data.records : []) as Record<string, unknown>[];

      for (const record of records) {
        // Rule: Only assignmentStatus == 99 is unsubmitted/undone assignment!
        const statusVal = record.assignmentStatus !== undefined ? Number(record.assignmentStatus) : -1;
        if (statusVal !== 99) {
          continue;
        }

        const assignmentId = String(record.id || record.assignmentId || '').trim();
        if (!assignmentId) continue;

        const title = String(record.title || record.assignmentTitle || '未命名作业').trim();
        const chapterName = record.chapterName ? String(record.chapterName).trim() : null;
        const rawDeadline = record.assignmentEndTime ? String(record.assignmentEndTime).trim() : null;

        assignments.push({
          assignmentId,
          siteId: course.siteId,
          courseName: course.siteName || '未知课程',
          title,
          chapterName,
          rawDeadline,
          assignmentStatus: 99,
          raw: record,
        });
      }

      const pages = Number(data.pages ?? 1);
      if (pages > UCloudClient.MAX_PAGES) {
        throw new UCloudError('INVALID_RESPONSE', '课程作业分页超过安全上限');
      }
      totalPages = Number.isFinite(pages) && pages > 0 ? pages : 1;
      if (records.length === 0 || currentPage >= totalPages) break;
      currentPage++;
    }

    return assignments;
  }

  async fetchAll(session: UCloudSession): Promise<UCloudFetchResult> {
    const courses = await this.fetchCourses(session);
    const allAssignments: RawUCloudAssignment[] = [];
    const successfulSiteIds = new Set<string>();
    const successfulAssignmentSiteIds = new Set<string>();
    const successfulQuizSiteIds = new Set<string>();
    const failedSiteIds = new Set<string>();

    for (const course of courses) {
      try {
        const assignments = await this.fetchAssignmentsForCourse(session, course);
        allAssignments.push(...assignments);
        successfulAssignmentSiteIds.add(course.siteId);
      } catch (err: unknown) {
        const uErr = err as UCloudError;
        if (uErr.code === 'REAUTH_REQUIRED' || uErr.code === 'INVALID_CREDENTIALS') {
          throw uErr;
        }
        failedSiteIds.add(course.siteId);
      }
      try {
        const quizzes = await this.fetchQuizzesForCourse(session, course);
        allAssignments.push(...quizzes);
        successfulQuizSiteIds.add(course.siteId);
      } catch (err: unknown) {
        const uErr = err as UCloudError;
        if (uErr.code === 'REAUTH_REQUIRED' || uErr.code === 'INVALID_CREDENTIALS') {
          throw uErr;
        }
        failedSiteIds.add(course.siteId);
      }
      if (successfulAssignmentSiteIds.has(course.siteId) && successfulQuizSiteIds.has(course.siteId)) {
        successfulSiteIds.add(course.siteId);
      }
    }

    if (courses.length > 0 && successfulAssignmentSiteIds.size === 0 && successfulQuizSiteIds.size === 0) {
      throw new UCloudError('UPSTREAM_NETWORK_ERROR', '所有课程待办接口均请求失败');
    }

    return {
      assignments: allAssignments,
      successfulSiteIds,
      successfulAssignmentSiteIds,
      successfulQuizSiteIds,
      failedSiteIds,
      allCoursesSucceeded: failedSiteIds.size === 0,
    };
  }

  async fetchQuizzesForCourse(session: UCloudSession, course: UCloudCourseRecord): Promise<RawUCloudAssignment[]> {
    const quizzes: RawUCloudAssignment[] = [];
    let currentPage = 1;
    let totalPages = 1;
    while (currentPage <= totalPages && currentPage <= UCloudClient.MAX_PAGES) {
      const query = new URLSearchParams({
        current: String(currentPage), size: '100', status: '-1',
        siteId: course.siteId, statusSelf: '全部',
      });
      const res = await safeFetch(`${UCloudClient.API_ORIGIN}/ykt-site/examination/list-stu?${query}`, {
        method: 'GET', headers: this.apiHeaders(session.accessToken),
      });
      if (!res.ok) {
        if (res.status === 401 || res.status === 403) {
          throw new UCloudError('REAUTH_REQUIRED', `拉取课程测验认证失效 (HTTP ${res.status})`);
        }
        throw new UCloudError('UPSTREAM_NETWORK_ERROR', `拉取课程测验失败 (HTTP ${res.status})`);
      }
      let rawRoot: unknown;
      try { rawRoot = await res.json(); } catch {
        throw new UCloudError('INVALID_RESPONSE', '课程测验响应格式解析异常');
      }
      const root = validateApiResponse(rawRoot, '课程测验');
      const data = root.data as Record<string, unknown> | undefined;
      if (!data || !Array.isArray(data.records)) {
        throw new UCloudError('INVALID_RESPONSE', '课程测验缺少分页记录');
      }
      const records = data.records as Record<string, unknown>[];
      for (const record of records) {
        if (record.statusSelf !== '未提交') continue;
        const id = String(record.id || '').trim();
        if (!id) continue;
        quizzes.push({
          taskType: 'QUIZ', assignmentId: `QUIZ:${id}`, siteId: course.siteId,
          courseName: course.siteName || '未知课程',
          title: String(record.title || '未命名测验').trim(), chapterName: null,
          rawDeadline: record.endAt ? String(record.endAt).trim() : null,
          assignmentStatus: 99, raw: record,
        });
      }
      const pages = Number(data.pages ?? 1);
      if (pages > UCloudClient.MAX_PAGES) {
        throw new UCloudError('INVALID_RESPONSE', '课程测验分页超过安全上限');
      }
      totalPages = Number.isFinite(pages) && pages > 0 ? pages : 1;
      if (currentPage >= totalPages) break;
      currentPage++;
    }
    return quizzes;
  }

  async fetchDetail(session: UCloudSession, assignmentId: string): Promise<string> {
    const url = `${UCloudClient.API_ORIGIN}/ykt-site/work/detail?assignmentId=${encodeURIComponent(assignmentId)}`;
    const res = await safeFetch(url, {
      method: 'GET',
      headers: this.apiHeaders(session.accessToken),
    });

    if (!res.ok) {
      if (res.status === 401 || res.status === 403) {
        throw new UCloudError('REAUTH_REQUIRED', `获取作业详情认证失效 (HTTP ${res.status})`);
      }
      throw new UCloudError('UPSTREAM_NETWORK_ERROR', `获取作业详情失败 (HTTP ${res.status})`);
    }

    let rawRoot: unknown;
    try {
      rawRoot = await res.json();
    } catch {
      throw new UCloudError('INVALID_RESPONSE', '作业详情响应格式解析异常');
    }
    const root = validateApiResponse(rawRoot, '作业详情');
    const data = (root.data || {}) as Record<string, unknown>;
    const content = String(data.content || data.description || data.assignmentDescription || '').trim();
    return content;
  }
}
