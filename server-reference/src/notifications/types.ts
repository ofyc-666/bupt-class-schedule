export interface NotificationSendResult {
  success: boolean;
  code?: number;
  message?: string;
}

export interface NotificationMessage {
  title: string;
  content: string;
}

/** Optional adapter; PushPlus is one possible implementation. */
export interface NotificationSender {
  send(token: string, title: string, content: string): Promise<NotificationSendResult>;
  formatNewAssignmentMessage(
    courseName: string, title: string, rawDeadline: string | null,
    chapterName: string | null, taskType?: 'ASSIGNMENT' | 'QUIZ',
  ): NotificationMessage;
  formatDeadlineChangedMessage(
    courseName: string, title: string, oldDeadline: string | null,
    newDeadline: string | null, taskType?: 'ASSIGNMENT' | 'QUIZ',
  ): NotificationMessage;
  formatDueReminderMessage(
    courseName: string, title: string, rawDeadline: string,
    hoursRemaining: 24 | 3, taskType?: 'ASSIGNMENT' | 'QUIZ',
  ): NotificationMessage;
}
