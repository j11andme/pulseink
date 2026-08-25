# PulseInk Security Policy

## Supported scope

PulseInk 当前是用于本地学习、演示和架构参考的产品级开源 MVP，不应直接暴露到公网或承载真实敏感数据。
仓库尚未提供正式的长期支持版本或安全补丁 SLA。

## Report a vulnerability

请不要在公开 Issue 中披露可利用细节、密钥或个人数据。优先通过 GitHub 仓库的 **Security → Report a
vulnerability** 创建私有 Security Advisory；若仓库尚未启用该功能，请先向维护者发送不含利用代码的
简要说明，等待私有沟通渠道。

报告建议包含受影响版本、入口、影响范围、复现前提和缓解建议。不要在未经允许的外部系统上进行测试。

## Secrets and local defaults

- Ark/Zhipu/Embedding Key、JWT Secret、数据库密码只通过环境变量注入；根 `.env` 已被 Git 忽略。
- `.env.example` 的账号与密码仅用于本地 `local` Profile，禁止用于公网部署。
- 浏览器容器只接触 Nginx 和 `/api`，不会获得模型 Key、数据库、Redis、Kafka 或 JWT 签名密钥。
- 构建上下文通过各应用 `.dockerignore` 排除 `.env`、`target`、`node_modules`、日志、本地数据和报告。

维护者发布版本前应轮换所有曾经暴露过的 Key，并使用专用密钥扫描工具检查完整 Git 历史，而不只是当前文件。

## Agent and tool boundaries

- 模型输出不是权限凭证。ToolRegistry 在 Java 侧再次执行角色白名单、Schema、风险、审批、超时和结果
  大小校验。
- 具有外部副作用或 Secret 风险的工具必须满足权限和人工审批条件。
- 内容只有人工批准后才能进入发布流程；Channel Sandbox 只模拟渠道，不会真实发布到社交平台。
- Trace 保存结构化调用、结果和失败阶段，不保存完整 Prompt、API Key、JWT 或 Chain-of-Thought。
- 外部文档和模型输出均按不可信输入处理；引用 ID 必须来自真实检索结果。

## Data and runtime boundaries

- MySQL 是业务事实源；Redis 缓存和 Elasticsearch 检索投影可重建。
- Redis Run Lease 用于降低多个 Backend 实例重复执行同一 Run 的风险，但当前未完成跨机房验证。
- Kafka 采用 at-least-once 语义，通过 eventId、Inbox/原始事件记录和业务幂等避免重复效果，不声称端到端
  Exactly Once。
- `docker compose down` 保留命名 Volume；`docker compose down -v` 会删除本地数据库、索引、消息和
  知识文件，只能在明确不再需要数据时执行。

## Not implemented

当前没有 TLS 终止、企业 SSO、细粒度多租户、Kubernetes、网络策略、WAF、集中式审计平台、合规认证、
多机房容灾或真实生产发布适配器。任何公网部署都需要在项目外补齐这些能力并完成独立安全评审。
