# API 通知系统 curl 测试文档

本文档假设服务运行在 `8088` 端口。

## 0. 启动服务

```bash
mvn spring-boot:run -Dspring-boot.run.arguments="--server.port=8088"
```

默认配置里有一个调用方：

- `X-Source-System: demo`
- 默认允许目标域名：`httpbin.org`、`example.com`

如果要更快观察重试，可以用下面的启动方式：

```bash
mvn spring-boot:run -Dspring-boot.run.arguments="--server.port=8088 --app.worker.scan-delay=1s --app.delivery.initial-backoff=1s --app.delivery.max-backoff=2s"
```

## 1. POST 通知任务

业务系统通过 `POST /notifications` 提交一个需要异步投递的外部 HTTP POST 请求。

```bash
curl -i -X POST http://localhost:8088/notifications \
  -H 'Content-Type: application/json' \
  -H 'X-Source-System: demo' \
  -d '{
    "idempotencyKey": "case-post-001",
    "targetUrl": "https://httpbin.org/anything",
    "method": "POST",
    "headers": {
      "Content-Type": "application/json"
    },
    "body": "{\"orderId\":\"10001\",\"event\":\"PAID\"}"
  }'
```

预期：

- 返回 `202 Accepted`
- 返回体里有 `notificationId`
- worker 后续会异步投递到 `https://httpbin.org/anything`

## 2. GET 通知任务

外部请求方法为 `GET` 时，参数放在 `targetUrl` 的 query string 里。

```bash
curl -i -X POST http://localhost:8088/notifications \
  -H 'Content-Type: application/json' \
  -H 'X-Source-System: demo' \
  -d '{
    "idempotencyKey": "case-get-001",
    "targetUrl": "https://httpbin.org/get?orderId=10001",
    "method": "GET",
    "headers": {
      "Accept": "application/json"
    },
    "body": ""
  }'
```

预期：

- 返回 `202 Accepted`
- 最终状态应变为 `SUCCESS`

## 3. PUT 通知任务

```bash
curl -i -X POST http://localhost:8088/notifications \
  -H 'Content-Type: application/json' \
  -H 'X-Source-System: demo' \
  -d '{
    "idempotencyKey": "case-put-001",
    "targetUrl": "https://httpbin.org/anything/10001",
    "method": "PUT",
    "headers": {
      "Content-Type": "application/json"
    },
    "body": "{\"orderId\":\"10001\",\"status\":\"SHIPPED\"}"
  }'
```

预期：

- 返回 `202 Accepted`
- 最终状态应变为 `SUCCESS`

## 4. 查询任务状态

把 `{notificationId}` 替换成提交接口返回的 ID。

```bash
curl -i -X GET http://localhost:8088/notifications/{notificationId} \
  -H 'X-Source-System: demo'
```

预期成功返回示例：

```json
{
  "notificationId": "...",
  "sourceSystem": "demo",
  "targetUrl": "https://httpbin.org/anything",
  "method": "POST",
  "status": "SUCCESS",
  "attemptCount": 1,
  "maxAttempts": 5
}
```

状态含义：

| 状态 | 说明 |
|---|---|
| `PENDING` | 等待投递或等待下次重试 |
| `PROCESSING` | worker 已领取，正在投递 |
| `SUCCESS` | 投递成功 |
| `FAILED` | 达到失败终态 |

## 5. 查询投递历史

```bash
curl -i -X GET http://localhost:8088/notifications/{notificationId}/attempts \
  -H 'X-Source-System: demo'
```

预期：

- 成功投递后至少有一条 `SUCCESS` attempt
- 失败重试后会有多条 `FAILED` attempt

## 6. 幂等重复提交

重复执行第 1 节的 POST 通知任务请求。

预期：

- 第一次返回 `202 Accepted`
- 第二次返回 `200 OK`
- `duplicate=true`
- `notificationId` 与第一次相同

## 7. 幂等冲突

使用同一个 `idempotencyKey`，但修改 payload。

```bash
curl -i -X POST http://localhost:8088/notifications \
  -H 'Content-Type: application/json' \
  -H 'X-Source-System: demo' \
  -d '{
    "idempotencyKey": "case-post-001",
    "targetUrl": "https://httpbin.org/anything",
    "method": "POST",
    "headers": {
      "Content-Type": "application/json"
    },
    "body": "{\"orderId\":\"10001\",\"event\":\"REFUNDED\"}"
  }'
```

预期：

- 返回 `409 Conflict`
- 不会新建任务

## 8. 不可重试失败：4xx

除 `408`、`429` 外，大多数 `4xx` 视为不可重试失败。

```bash
curl -i -X POST http://localhost:8088/notifications \
  -H 'Content-Type: application/json' \
  -H 'X-Source-System: demo' \
  -d '{
    "idempotencyKey": "case-400-001",
    "targetUrl": "https://httpbin.org/status/400",
    "method": "POST",
    "headers": {},
    "body": "",
    "maxAttempts": 3
  }'
```

预期：

- 提交返回 `202 Accepted`
- worker 投递一次后任务变为 `FAILED`
- `attemptCount=1`

## 9. 可重试失败：5xx

建议使用第 0 节的快速重试启动方式。

```bash
curl -i -X POST http://localhost:8088/notifications \
  -H 'Content-Type: application/json' \
  -H 'X-Source-System: demo' \
  -d '{
    "idempotencyKey": "case-500-001",
    "targetUrl": "https://httpbin.org/status/500",
    "method": "POST",
    "headers": {},
    "body": "",
    "maxAttempts": 2
  }'
```

预期：

- 提交返回 `202 Accepted`
- 失败后会按退避策略重试
- 最终变为 `FAILED`
- `attemptCount=2`
- `/attempts` 返回两条失败记录

## 10. 人工重试 FAILED 任务

只允许对 `FAILED` 任务调用。

```bash
curl -i -X POST http://localhost:8088/notifications/{notificationId}/retry \
  -H 'X-Source-System: demo'
```

预期：

- 返回 `202 Accepted`
- 任务回到 `PENDING`
- `attemptCount` 重置为 `0`

## 11. 缺少调用方身份

```bash
curl -i -X POST http://localhost:8088/notifications \
  -H 'Content-Type: application/json' \
  -d '{
    "idempotencyKey": "case-auth-001",
    "targetUrl": "https://httpbin.org/anything",
    "method": "POST",
    "headers": {},
    "body": ""
  }'
```

预期：

- 返回 `401 Unauthorized`

## 12. 非白名单域名

```bash
curl -i -X POST http://localhost:8088/notifications \
  -H 'Content-Type: application/json' \
  -H 'X-Source-System: demo' \
  -d '{
    "idempotencyKey": "case-domain-001",
    "targetUrl": "https://example.org/hook",
    "method": "POST",
    "headers": {},
    "body": ""
  }'
```

预期：

- 返回 `400 Bad Request`
- 原因是 `example.org` 不在 `demo` 的目标域名白名单内

## 13. 保留 Header 校验

`X-Idempotency-Key` 由平台自动透传，调用方不能覆盖。

```bash
curl -i -X POST http://localhost:8088/notifications \
  -H 'Content-Type: application/json' \
  -H 'X-Source-System: demo' \
  -d '{
    "idempotencyKey": "case-reserved-header-001",
    "targetUrl": "https://httpbin.org/anything",
    "method": "POST",
    "headers": {
      "X-Idempotency-Key": "caller-should-not-set-this"
    },
    "body": ""
  }'
```

预期：

- 返回 `400 Bad Request`
