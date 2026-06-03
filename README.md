# API 通知投递服务（Notification Delivery Service）

一个内部服务：接收各业务系统提交的「向外部供应商发 HTTP 通知」的请求，负责把它**尽可能可靠地投递**到目标地址。
业务方提交完即可返回，不需要等外部 API 的结果。

> 配套文档：第一版 AI 方案见 [`ai-design-initial-version.md`](./ai-design-initial-version.md)，
> 对 AI 输出的取舍过程见 [`AI使用说明.md`](./AI使用说明.md)。

---

## 一、我对问题的理解

### 1.1 这个系统到底要解决什么

公司内部有多个业务系统，在关键事件发生时（注册成功、付款成功、下单成功……）要去调外部供应商的 HTTPS API
做通知。痛点是：

- **每家供应商的 URL、Header、Body 格式都不一样**，各业务系统各自硬调，重试/失败处理逻辑到处重复造轮子；
- 外部 API 可能**慢、超时、临时挂掉**，业务主流程不该被它拖住，更不该因为它失败而丢掉通知；
- 业务方**不关心外部 API 的返回值**，只要求「这条通知最终能稳定送达」。

所以这个服务的本质是一个 **「可靠的、异步的 HTTP 投递中台」**：把"可靠送达"这件事从各业务系统里抽出来，
统一收口、统一重试、统一观测。它的核心价值不是功能多，而是**可靠性 + 把复杂度收在一处**。

### 1.2 系统边界

**我选择解决的：**

- 接收业务系统提交的外部 HTTP 通知请求（在调用方授权范围内的 targetUrl / method / headers / body）；
- 请求持久化，**进程崩溃/重启后不丢、能接着投**；
- 异步投递，提交即返回，调用方不阻塞等待外部 API；
- 失败自动重试（指数退避 + 抖动）、状态可追踪、达到上限后转终态；
- 投递的**安全管控**（防 SSRF、调用方鉴权）——这是这类"代发任意请求"服务的正确性的一部分；
- 基本可观测性：状态/尝试历史查询、关键指标、最终失败告警。

**我明确选择不解决（及原因）：**

| 不做 | 原因 |
|---|---|
| 不保证 exactly-once，只做 at-least-once | 外部系统不可控 + 网络不确定，重试场景下重复无法消除（详见 §3.2） |
| 不保证外部业务真的成功 | 只能依据 HTTP 响应/网络结果判断"投递"结果，业务是否成功是下游的事 |
| 不做供应商模板编排 / 格式映射 | 格式知识属于业务域，过早内化进平台会随供应商数量膨胀（详见 §3.3） |
| 不做多租户隔离 / 计费 / 租户权限模型 | 面向公司内部少量系统，第一版不需要；但**仍要有**调用方鉴权 + 白名单 |
| 不做大规模分布式调度 | 第一版定位低到中等吞吐，DB + 应用内定时扫描 worker 足够（详见 §3.1） |

---

## 二、整体架构与核心设计

### 2.1 架构总览

```
业务系统
   │  POST /notifications（校验 + 落库即返回 notificationId）
   ▼
┌─────────────────────────────────────────────┐
│  通知投递服务                                  │
│                                               │
│  接入层 Controller ──► 校验/鉴权/SSRF 检查 ──► 落库  │
│                                               │
│  存储层：notification_task / delivery_attempt  │
│            ▲                    │              │
│            │  SKIP LOCKED 领取   │  写尝试记录   │
│            │  + 租约 locked_until│              │
│  调度 Worker（虚拟线程并发投递）─┘              │
│            │                                  │
│            ▼  HTTP 调用                        │
└────────────┼──────────────────────────────────┘
             ▼
        外部供应商 API
```

技术栈：Java 21 + Spring Boot 3.x，存储用关系型数据库（开发演示可用 H2，生产用 PostgreSQL；
涉及 `SKIP LOCKED`、锁竞争、并发领取的测试用 PostgreSQL Testcontainers，避免 H2 锁语义和生产不一致）。
对外 IO 用虚拟线程承载，统一控制最大并发。

### 2.2 数据模型

**`notification_task`（任务主表）**

| 字段 | 说明 |
|---|---|
| id | 内部 notificationId |
| source_system | 调用方标识，从认证信息推导（如 API key / mTLS / JWT subject），不能直接信任请求体 |
| target_url / method / headers / body | 投递内容；必须可恢复用于重试，敏感 header/body 字段加密存储，日志和查询展示时再脱敏 |
| request_fingerprint | 规范化原始 payload 的 SHA-256，**在脱敏/加密之前**计算；用于幂等重复提交时判定 payload 是否一致（脱敏有损、密文比对昂贵，故不能拿存储值比对） |
| idempotency_key | 幂等键，**全量必填**（平台无法替调用方判定哪条"关键"，故统一要求）；`(source_system, idempotency_key)` 唯一索引，防上游重复提交 |
| status | `PENDING / PROCESSING / SUCCESS / FAILED` |
| attempt_count / max_attempts | 已尝试次数 / 上限 |
| next_attempt_at | 下次可投递时间（退避调度用） |
| locked_until | 租约到期时间（崩溃回收用） |
| version | 乐观锁版本号；领取时读取，写回时作为更新条件，防止租约过期的"慢 worker"覆盖已被重捞任务的状态 |
| created_at / updated_at | 时间戳 |

**`delivery_attempt`（投递尝试明细）**

| 字段 | 说明 |
|---|---|
| id / notification_id | 关联任务 |
| attempt_no | 第几次尝试，等于写入结果时自增后的 `attempt_count` |
| status | `SUCCESS / FAILED`：本次投递拿到结果后才写入这条记录 |
| response_status / error_type / error_detail | HTTP 状态码 / 错误分类 / 摘要 |
| latency_ms | 本次 HTTP 调用耗时 |
| created_at | 记录时刻（投递结束、拿到结果后写入） |

### 2.3 核心流程

1. **提交**：`POST /notifications` → 参数校验 + 调用方鉴权（认证结果推导 `source_system`）+ **SSRF 检查** →
   写 `notification_task`（状态 `PENDING`）→ 返回 `notificationId`。**落库成功才返回**，否则报错让调用方
   带同一 `idempotencyKey` 重试。`idempotencyKey` 全量必填，配合 `(source_system, idempotency_key)` 唯一索引，
   提交超时重试也不会重复建任务。命中已有键时按 `request_fingerprint`（**脱敏/加密前**对规范化 payload 算的 SHA-256）
   判定：一致则返回已有任务（含终态任务），不一致返回 `409 Conflict`，不能静默复用旧任务。
2. **领取**：Worker 周期性扫描两类任务：`status = PENDING 且 next_attempt_at <= now`，以及
   `status = PROCESSING 且 locked_until < now` 的租约过期任务。用 `SELECT ... FOR UPDATE SKIP LOCKED`
   领取一批，置 `PROCESSING`、写 `locked_until = now + lease`、`version++`，并记下本次读到的 `version`。
   领取本身不增加 `attempt_count`，避免 worker 在真正发起 HTTP 前崩溃也消耗重试次数。
3. **投递**：对 targetUrl 发起 HTTP 调用。平台会把
   `idempotency_key` 透传到下游请求头（如 `X-Idempotency-Key`），该 header 属于平台保留 header，调用方
   不能覆盖；如果某个供应商不识别该 header，它只能降低重复影响，不能保证下游去重。
4. **结果处理**（拿到结果后 `attempt_count++`，并写一条 `delivery_attempt`，`attempt_no = attempt_count`）：
   所有对任务的写回都带乐观锁条件 `WHERE id = ? AND version = ?`（领取时读到的 `version`）：
   - 成功（2xx）→ attempt 记 `SUCCESS`，任务置 `SUCCESS`；
   - 可重试失败 → attempt 记 `FAILED`；如果 `attempt_count >= max_attempts`，任务置 `FAILED`，
     否则计算下次 `next_attempt_at`，任务回 `PENDING`；
   - 不可重试失败 → attempt 记 `FAILED`，任务直接 `FAILED`。
   - **写回影响行数为 0**：说明租约已被其他 worker 接管（自己是那个"慢 worker"），直接丢弃本次回写，
     不改任务状态、不累加 `attempt_count`，避免覆盖已被重捞任务的状态。
   - 若 worker 在发出请求后、写结果前就崩溃：不留 attempt 记录，也不消耗重试次数；任务靠租约过期被重投
     （回到步骤 2）。这正是 at-least-once 的预期，重复由下游幂等键兜底，不需要额外的"已发起未回填"状态。
5. **观测/补救**：查询接口看状态与历史；按失败率/积压量/最老任务延迟等指标触发聚合告警；必要时
   `POST /notifications/{id}/retry` 人工重投。

### 2.4 可靠性与失败处理（需求重点）

**投递语义：at-least-once（至少一次）。** 对已接收任务尽力投递：至少发起一次，失败按策略重试，可能重复；
超过重试上限后进入 `FAILED`，不承诺一定最终送达，也不承诺 exactly-once（理由见 §3.2）。

**重试策略：**

- **退避**：指数退避 + **抖动（jitter）**，例如 1min / 5min / 15min / 1h，最多 5 次。加抖动是为了避免一批同时失败的任务
  在同一时刻集体重试，把下游再打挂。
- **错误分类**：
  - 可重试：连接失败、超时、`5xx`、`429`（限流，尊重 `Retry-After`）、`408`；
  - 不可重试：其余 `4xx`（参数/鉴权类，重试也没用）；
  - 注意区分连接超时（请求大概率没送达，重试安全）与读取超时（可能已送达，重试会放大重复——靠下游幂等兜底）。

**并发与崩溃恢复：**

- 多实例 worker 用 `SKIP LOCKED` 领取，天然不会两个实例抢到同一条 —— **第一版就支持多实例**，不留到"以后"。
- `PROCESSING` 任务带 `locked_until` 租约；worker 崩溃后租约到期，任务被重新捞起，**不会永久卡死**。
- **租约时长必须 > 单次投递最大耗时（连接超时 + 读超时 + 余量）**。否则 worker 还活着、只是投递慢，租约就过期被
  另一个 worker 重捞并发再投一次，把"崩溃才回收"退化成"慢就重复"。这是 at-least-once 下可接受但应主动收敛的重复来源。
  注意：`version` 乐观锁只防止慢 worker **覆盖任务状态**（lost update），并不能阻止那一次**重复投递**本身——
  重复仍由下游幂等键兜底，租约时长用来把这种重复压到最少。
- worker 在"已发出请求、未写结果"时崩溃，不会留下 attempt 记录；任务靠租约过期重投即可，第一版不引入
  "已发起未回填"状态和对应的回收任务，保持状态机精简（重复由下游幂等键兜底）。

**长期不可用：**

- `attempt_count` 达到 `max_attempts` 后进 `FAILED`，保留全部 attempt 记录与最后错误；
- 暴露积压量、失败率、投递延迟、最老待投递任务年龄等指标，并按 `source_system` / 目标域名聚合告警；
  避免每条失败任务单独告警造成告警风暴，也避免"没人盯 FAILED 表 = 等于没处理"；
- 提供 `POST /notifications/{id}/retry` 人工重投兜底。

### 2.5 安全

- **防 SSRF**：targetUrl 只允许 https、限制端口、拒绝 IP literal、解析后拒绝内网/保留网段
  （127/10/172.16/192.168/169.254 等）、按 `source_system` 配可达域名白名单；默认不跟随重定向，
  如果必须跟随，每一次跳转后的 URL 和 DNS 解析结果都重新校验，防跳转到内网地址；
- **SSRF 校验必须在"每次投递前"做，而不是只在提交时**：本服务是异步的，提交与投递、以及多次重试之间
  隔着几分钟到几小时，提交时通过校验的域名，投递时可能已被改指向内网 IP（DNS rebinding）。因此每次投递要
  重新解析并校验解析出的 IP，禁止 IP literal，默认不跟随重定向；如果必须跟随，每次跳转都重新做 URL 和 IP 校验。
- **真正的出网边界放在网络层，应用层校验只当 best-effort**：应用里"重新解析校验"和 HttpClient 真正建连时的解析
  是两次独立 DNS 查询，中间仍有 DNS rebinding 的 TOCTOU 窗口；与其在应用代码里 pin IP（还要处理 SNI / Host /
  证书校验等细节）把它堵死，不如把**硬边界放在 egress 防火墙 / 正向代理**——只放行已批准的供应商域名或 IP 段，
  内网地址根本路由不出去。这样既彻底，又比 pin IP 实现简单；应用层校验作为 defense-in-depth 保留即可；
- **调用方鉴权**：从认证信息识别 `source_system` 身份，不能信任请求体里的来源标识；内部系统也不默认全可信；
- **凭证保护**：headers/body 可能含 token，且重试时需要还原原始请求，所以用于投递的数据必须可恢复；
  `Authorization`、`Cookie`、`X-Api-Key` 等敏感字段第一版就应加密存储或存凭证引用，日志、查询接口和展示层再脱敏，
  并且绝不打印明文凭证。

### 2.6 数据生命周期

`delivery_attempt` 和成功任务会无限增长，需要归档/TTL（如 SUCCESS 任务保留 N 天后归档冷表或删除），按时间分区便于清理。

### 2.7 对外接口

| 接口 | 用途 |
|---|---|
| `POST /notifications` | 提交通知，返回 notificationId |
| `GET /notifications/{id}` | 查任务状态 |
| `GET /notifications/{id}/attempts` | 查投递历史 |
| `POST /notifications/{id}/retry` | 终态任务人工重投 |

> **访问隔离**：以上所有接口都按认证推导的 `source_system` 做归属校验，**只能访问/操作自己提交的任务**，
> 越权一律返回 404（不泄露任务是否存在）。任务里可能含脱敏/加密的 headers/body 凭证，读接口和提交接口必须用
> 同一套鉴权与 scope 约束，否则凭证会从查询口泄露。

---

## 三、关键工程决策与取舍

### 3.1 第一版用「DB 任务表 + @Scheduled 轮询 Worker」，不上 MQ

- **决策**：用关系型数据库存通知任务，dispatcher 用 Spring `@Scheduled` 周期性扫描（含租约过期任务的重捞），领取后投递；归档清理同样用 `@Scheduled`。
- **使用的基础设施**：只有 PostgreSQL 一个有状态组件作为持久化任务表；调度就是应用内的 `@Scheduled` 定时器，无额外中间件。
- **为什么选择它**：作业/MVP 假设是低到中等吞吐，DB 方案已能把"持久化 + 重试 + 状态可查 + 事务一致"一次性解决；
  MQ 的收益（高吞吐、高扇出、解耦）在当前量级体现不出来，反而引入运维成本、消息去重、消费位点管理等新复杂度。
- **为什么不用 Quartz 这类集群调度**：dispatcher 的并发安全已经由 `SKIP LOCKED + locked_until` 兜住——
  **就算每个实例都各自 `@Scheduled` 同时跑，也不会重复领取同一条任务**，因此不需要 Quartz 的"同一时刻只让一个节点跑"的集群协调。
  为这点引入 Quartz JobStore（额外的表、配置、运维）属于不必要的复杂度。
- **不使用 MQ 时的替代方案**：就是当前方案，把 `notification_task` 当作持久化任务队列，用 `next_attempt_at`
  做延迟调度，用 `SKIP LOCKED + locked_until` 做并发领取和崩溃恢复。
- **代价**：轮询有固有投递延迟（取决于扫描间隔），吞吐受单库能力限制。
- **何时演进**：积压持续增长、投递延迟扛不住、worker 加机器被 DB 锁等待/扫描成本卡住时，再换 Kafka/RabbitMQ/Redis Stream + 消费组。
  并发领取相关测试必须在 PostgreSQL 上跑，不能只靠 H2 验证。

### 3.2 投递语义选 at-least-once，而不是 exactly-once

- **决策**：对外承诺 at-least-once；服务内部用状态机 + 唯一约束 + 租约 + 任务锁**尽量减少**重复，但不承诺零重复。
- **为什么 exactly-once 做不到**：外部系统不归我管，只要会重试 + 网络不可靠，重复就消不掉——响应丢失时无法区分
  "请求没送到"还是"送到了但回执丢了"。真正的 exactly-once 需要收发双方共建去重/确认协议，而供应商是第三方、
  我们连返回值都拿不到，不具备条件。**连服务自己这侧也消不掉重复**（发完请求、状态没落库就崩，恢复后照样重投）。
- **因此**：把 `idempotencyKey` 透传到下游，让下游按业务语义去重——这不是锦上添花，是 at-least-once 语义下的必要补充。
- **替代（被否）**：at-most-once（不重试）能避免重复，但会丢通知，违背"可靠送达"的核心目标。

### 3.3 供应商适配责任放在调用方，平台只做「忠实投递」

- **决策**：平台接收调用方拼好的 `targetUrl/method/headers/body`，**不做模板编排 / 格式映射**。
- **为什么**：供应商格式知识本就属于业务域，平台做模板会随供应商数量线性膨胀成自身复杂度；保持平台"通用、薄"更利于稳定。
- **代价**：多个业务系统若都对接同一供应商，会有重复适配。
- **何时演进**：当出现多个系统重复对接同一供应商，或签名/鉴权/Body 映射开始大量复制粘贴时，再把「统一模板配置 / connector」抽到平台侧。

### 3.4 安全（防 SSRF + 调用方鉴权）提到第一版必做

- **决策**：URL 白名单/内网拦截/仅 https + 调用方身份鉴权，第一版就做，而不是放进"以后再加密脱敏"。
- **为什么**：一个能替你请求任意 URL 的服务，安全就是正确性的一部分。即便是内部几个系统在调，也不能默认全可信，
  否则一旦某个调用方被打穿，这个服务就是现成的内网探测跳板。

### 3.5 并发正确性（SKIP LOCKED + PROCESSING 租约回收）提到第一版

- **决策**：第一版就支持多实例安全领取和崩溃恢复，不放进演进路线。
- **为什么**：「可靠投递」首先要求服务自己在多实例、崩溃重启下不丢不卡。`SKIP LOCKED` 成本极低，
  不做的话只要部署两个实例就会重复投递；不做租约回收，worker 一崩任务就永久卡在 PROCESSING。这是地基不是优化。

### 3.6 接收契约显式化

- **决策**：`POST /notifications` 保证"落库成功才返回 notificationId"；`idempotencyKey` **全量必填**，
  并约定调用方在超时/未拿到 ID 时带同一 `idempotencyKey` 重试。同一幂等键重复提交时，按 `request_fingerprint`
  判定：payload 一致返回已有任务，不一致返回 `409 Conflict`。
- **幂等键保留窗口**：幂等去重保留 N 天（与数据生命周期 §2.6 对齐）。窗口内命中**终态任务（含 `FAILED`）也返回该任务、不新建**——
  想重发失败的通知请走 `POST /notifications/{id}/retry`，而不是复用同一幂等键提交；窗口过期后该键可被视为新任务。
- **为什么**：让"接收成功"的边界对调用方清晰、可依赖，配合幂等键避免重复建任务。统一必填而不是"关键才填"，
  是因为平台无法替调用方判定一条通知是否"关键"，与其留一条无法强制的软约定，不如全量要求，把语义焊死。

### 3.7 AI 建议中我认为过度、第一版不采纳的设计

这些点不是永远不做，而是第一版不做。判断依据是：它们会显著增加基础设施、数据模型或运维复杂度，但当前需求还没有给出足够规模或复杂度来证明这笔成本值得。

| AI/常见方案建议 | 第一版是否采纳 | 判断依据 |
|---|---|---|
| 一开始就上 Kafka/RabbitMQ | 不采纳 | 当前只是低到中等吞吐的可靠 HTTP 投递，DB 任务表已经能闭环；MQ 会额外引入消费确认、死信、重复消息、运维成本 |
| 完整死信队列 + 管理后台 | 不采纳完整版本 | 第一版只做 `FAILED` 状态、查询接口、聚合告警和手动 retry；等失败处理需要频繁人工运营时再做后台 |
| 供应商模板编排 / 流程引擎 | 不采纳 | 供应商格式属于业务域，过早内化到平台会让平台背上大量变化；等多个系统重复对接同一供应商时再抽 connector |
| 多租户隔离 / 计费 / 租户权限模型 | 不采纳 | 当前面向内部少量系统，不需要租户产品化能力；但调用方鉴权、source 白名单和 URL 白名单第一版必须做 |
| 引入 Quartz 集群调度 / 重量级工作流引擎 | 不采纳 | dispatcher 等定时任务用 `@Scheduled` 即可，多实例并发安全由 `SKIP LOCKED` 保证，不需要 Quartz 的集群协调；通知任务由业务表状态机管理，避免引入额外调度中间件 |
| callback / completion webhook | 不采纳 | 会引入第二条可靠投递链路，也要重试、鉴权、状态记录和 SSRF 防护；第一版用 `notificationId` 查询和告警闭环 |

---

## 四、演进路线（流量/复杂度上来后）

1. DB 轮询扫描 → MQ / Redis Stream + 消费组，降低投递延迟、提升吞吐；
2. 引入死信队列 + 失败任务管理后台；
3. 统一供应商模板 / connector 配置，收敛重复适配；
4. 多实例分片、按供应商维度限流 / 熔断 / 并发控制；
5. 敏感 Header/Body 加密存储与字段级脱敏强化。

---

## 五、当前第一版实现

代码实现是一个 Java 21 + Spring Boot 3.x Maven 项目，使用 Spring JDBC 操作
`notification_task` / `delivery_attempt` 两张表；本地默认用 H2，生产可切 PostgreSQL。

常用命令：

```bash
mvn test
mvn spring-boot:run
```

默认配置里有一个 demo 调用方：请求需要带 `X-Source-System: demo`；demo 的目标域名白名单为
`example.com`、`httpbin.org`。如果本地要打 mock server，可以临时打开：

```bash
mvn spring-boot:run -Dspring-boot.run.arguments="--app.security.allow-local-targets=true --app.security.sources.demo.allowed-domains[0]=localhost"
```
