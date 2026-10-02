# 后台同步与提醒参考实现

本目录提供后台定时同步和提醒所需的核心逻辑，包括 UCloud 登录与任务读取、任务 Diff、部分同步保护、提醒策略与去重，以及可选的 PushPlus 通知。

要实际运行这部分功能，需要在 Node.js 20+ 环境中接入数据库 Repository、定时调度、账号配置和服务入口。

```text
自行提供的 scheduler
        ↓
    SyncService
    ├── UCloudClient（CAS、OAuth、课程、作业、测验）
    ├── IDbRepository（由部署者实现）
    └── NotificationSender（可选，PushPlusClient 是一个实现）
```

`SyncService` 负责单飞租约、作业与测验独立基线、快照差异、部分成功时保留失败来源任务，以及新任务、截止变化、24 小时和 3 小时提醒的去重。`safeFetch` 只允许指定 HTTPS 主机，CAS 跳转的协议、主机和端口也会校验。加密模块使用标准 Web Crypto 的 AES-256-GCM、随机 IV 和 SHA-256。

## 接入时需要完成

1. 实现 `src/storage/repository.ts` 中的 `IDbRepository`。可使用 SQLite、PostgreSQL、MySQL 等。`acquireSyncLease` 必须是跨进程原子操作，通知去重键应有唯一约束；不要把测试用内存仓库用于生产。
2. 配置定时任务和服务入口，例如 VPS、NAS 或支持 Node.js 的 Serverless 环境。服务入口负责选择用户并调用 `SyncService.syncUserAssignments()`。
3. 安全配置 `MASTER_ENCRYPTION_KEY`：由安全随机源生成 32 字节（64 个十六进制字符），放在部署环境的密钥管理中。保存云邮密码和 PushPlus Token 前使用 `encryptText` 加密，切勿写日志或提交到仓库。
4. 根据实际部署方式补充用户配置、密码更新、权限管理，以及所需的服务 API、账户绑定页面、数据库迁移和调度入口。
5. 如果需要 PushPlus 通知，配置 PushPlus Token 并传入 `PushPlusClient`；不使用通知时，`SyncService` 的通知发送器可以传入 `null`。

## 验证

```sh
npm install
npm test
npm run typecheck
```

测试中的账号、密钥和 Token 都是假数据；请勿把真实凭据写入源码、测试或异常文本。
