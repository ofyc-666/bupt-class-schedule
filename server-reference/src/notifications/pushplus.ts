import { safeFetch } from '../net/safeFetch';
import { NotificationSender } from './types';

export interface PushPlusSendResult {
  success: boolean;
  code?: number;
  message?: string;
}

export class PushPlusClient implements NotificationSender {
  private static SEND_URL = 'https://www.pushplus.plus/send';

  async send(token: string, title: string, content: string): Promise<PushPlusSendResult> {
    if (!token) {
      return { success: false, message: 'PushPlus token 为空' };
    }

    try {
      const res = await safeFetch(PushPlusClient.SEND_URL, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          token,
          title,
          content,
          template: 'markdown',
          channel: 'wechat',
        }),
      });

      if (!res.ok) {
        return {
          success: false,
          code: res.status,
          message: `PushPlus HTTP 请求失败 (${res.status})`,
        };
      }

      const json = await res.json() as Record<string, unknown>;
      const code = Number(json.code ?? -1);
      if (code === 200) {
        return { success: true, code: 200 };
      } else {
        return {
          success: false,
          code,
          message: `PushPlus 业务返回错误 (${code})`,
        };
      }
    } catch {
      return {
        success: false,
        message: '发送 PushPlus 通知失败',
      };
    }
  }

  formatNewAssignmentMessage(courseName: string, title: string, rawDeadline: string | null, chapterName: string | null, taskType: 'ASSIGNMENT' | 'QUIZ' = 'ASSIGNMENT'): { title: string; content: string } {
    let content = `**${courseName}**\n\n${title}`;
    if (chapterName) {
      content += `\n\n章节：${chapterName}`;
    }
    content += `\n\n截止时间：${rawDeadline || '无明确截止时间'}`;

    return {
      title: `【北邮课表】发现新${taskType === 'QUIZ' ? '测验' : '作业'}`,
      content,
    };
  }

  formatDeadlineChangedMessage(courseName: string, title: string, oldDeadline: string | null, newDeadline: string | null, taskType: 'ASSIGNMENT' | 'QUIZ' = 'ASSIGNMENT'): { title: string; content: string } {
    const content = `**${courseName}**\n\n${title}\n\n原截止时间：${oldDeadline || '未知'}\n新截止时间：${newDeadline || '无明确截止时间'}`;
    return {
      title: `【北邮课表】${taskType === 'QUIZ' ? '测验' : '作业'}截止时间已更新`,
      content,
    };
  }

  formatDueReminderMessage(courseName: string, title: string, rawDeadline: string, hoursRemaining: 24 | 3, taskType: 'ASSIGNMENT' | 'QUIZ' = 'ASSIGNMENT'): { title: string; content: string } {
    const tag = hoursRemaining === 3 ? '紧急提醒 (3小时内)' : '截止提醒 (24小时内)';
    const content = `**${courseName}**\n\n${title}\n\n截止时间：${rawDeadline}`;
    return {
      title: `【北邮课表】${taskType === 'QUIZ' ? '测验' : '作业'}${tag}`,
      content,
    };
  }

  formatTestMessage(): { title: string; content: string } {
    return {
      title: '【北邮课表】微信提醒测试',
      content: '恭喜！你的 PushPlus 微信提醒已配置成功。\n\n当有新作业发布或作业截止时间变更时，将会通过此渠道提醒你。',
    };
  }
}
