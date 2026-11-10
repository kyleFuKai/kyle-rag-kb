# kyle-rag-kb

基于 Spring AI 的企业级 RAG 知识库服务：**文档入库 → 解析切分 → 向量化 → 混合检索（向量 + 全文 + HyDE + 重排）→ 生成带引用的回答**，并提供会话管理、效果评估与用户反馈闭环。

## 功能概览

| 能力 | 说明 |
|---|---|
| 文档入库 | 上传 PDF / DOCX / MD / TXT → MinIO 存原件 → 异步解析（按格式分派）→ 切分 → 向量化 → pgvector 落库 |
| 混合检索 | 向量（语义）+ 全文（精确词）双路召回 → RRF 融合 → HyDE 查询改写 → cross-encoder 精排 → 置信度过滤 → Token 预算裁剪 |
| 问答 | 同步与 SSE 流式两形态；答案带 `(来源:[参考N])` 引用标注；多轮会话上下文 |
| 权限 | Sa-Token 登录 + 知识库级 RBAC（公开库 / 部门授权 / 用户授权 / 管理员），覆盖检索、文档、反馈、评估全链路 |
| 评估 | 标准问答对数据集 → 跑评估产出 Hit Rate / MRR / 忠实度 → 按版本对比调参效果 |
| 反馈 | 点赞 / 点踩 + 文字意见，差评样本进入评估候选 |
| 成本控制 | embedding 按内容哈希缓存、查询结果缓存（Redis，10 分钟）、三维度 Token 计量与成本估算 |
| 可观测 | traceId 全链路日志、Micrometer + Prometheus 指标、业务异常统一结构化返回 |

## 技术栈

- Java 21 / Spring Boot 3.5.11 / Spring AI 1.1.2
- 大模型：MiniMax（对话）｜ 阿里百炼：向量化（text-embedding-v4，1024 维）+ 重排（qwen3-rerank）
- 存储：PostgreSQL + pgvector（业务数据 + 向量）、Redis（缓存 + 登录态 + 统计）、MinIO（原始文件）
- 认证：Sa-Token（登录态存 Redis）｜ 密码：BCrypt ｜ 参数校验：Jakarta Validation
- 监控：Actuator + Prometheus（Micrometer）｜ 线程池：业务池 / 索引池隔离

## 架构

```
                      ┌──────────── 上传 ────────────┐
文件 → MinIO          │  API /kb/{id}/documents      │
      (原件)          └──────────────┬───────────────┘
                                     ↓
        解析(PDFBox/POI/正则) → 切分(结构感知 / 滑动窗口) → 向量化(百炼)
                                     ↓
                    kb_doc_chunk(pgvector, HNSW 索引) + kb_doc_chunk.doc_version

                      ┌──────────── 提问 ────────────┐
问句 → 权限过滤 → 缓存 → HyDE 假设答案                      │
                     ↓                                    │
        向量路 top20 ┐                                   │
        全文路 top20 ┴→ RRF 融合 → 精排(qwen3-rerank, 800ms 超时降级)
                     ↓
              置信度过滤 → Token 预算裁剪 → 生成(MiniMax) → 引用溯源
```

**入库是异步的**：上传接口立即返回 `docId + PENDING`，索引在 `indexTaskExecutor` 线程池执行，前端凭 `docId` 轮询 `/status`。
**检索管道只有一份实现**：`FullRagPipeline.prepare()` 完成「权限 → 缓存 → 检索 → 精排 → 过滤 → 裁剪」，同步与流式共用，差异仅在「怎么生成、怎么输出、要不要落库」。

## 快速开始

### 1. 克隆与配置

```bash
git clone https://gitee.com/xingzhewk/kyle-rag-kb.git
cd kyle-rag-kb

copy .env.example .env    # Windows
cp .env.example .env      # Linux / Mac
```

`.env` 需要填的值（全部敏感信息走环境变量，不入库）：

| 变量 | 说明 |
|---|---|
| `DB_HOST` / `DB_USERNAME` / `DB_PASSWORD` | PostgreSQL（库名固定 `ragkb`） |
| `REDIS_HOST` / `REDIS_PASSWORD` | Redis |
| `MINIO_ENDPOINT` / `MINIO_ACCESS_KEY` / `MINIO_SECRET_KEY` | MinIO（桶名固定 `rag-documents`） |
| `MINIMAX_API_KEY` | MiniMax，对话模型 |
| `DASHSCOPE_API_KEY` | 百炼，向量化 + 重排共用 |
| `RERANKER_ENDPOINT` / `RERANKER_API_KEY` / `RERANKER_MODEL` | 重排接口与模型 |

### 2. 网络连通方式（二选一）

**A. 直连**：数据库 / Redis / MinIO 端口在云安全组放行你的公网 IP，`.env` 里填服务器 IP。

**B. SSH 隧道（推荐，IP 变了也不受影响）**：安全组只放行 22，其余端口不对公网暴露。

```bash
# 本机开隧道，把 5432/6379/9100/9101/3306 映射到云服务器
ssh -N -L 5432:127.0.0.1:5432 -L 6379:127.0.0.1:6379 \
       -L 9100:127.0.0.1:9100 -L 9101:127.0.0.1:9101 root@<服务器IP>
```

此时 `.env` 里三个地址都填 `127.0.0.1`。

### 3. 初始化数据库

在 `ragkb` 库中执行 `src/main/resources/db/schema.sql`（建 11 张表 + pgvector 扩展 + HNSW 索引 + 全文检索触发器）。

### 4. 启动

```bash
mvn spring-boot:run
```

服务在 http://localhost:8080，健康检查 `/actuator/health`。

`dev` profile（`application.yml` 默认激活）会在启动时向 `users` 表播种三个演示账号，密码统一 `demo123`：

| 用户名 | 部门 | 角色 |
|---|---|---|
| `hr001` | HR | MEMBER |
| `tech001` | TECH | MEMBER |
| `admin` | ALL | ADMIN |

生产部署请用 `--spring.profiles.active=prod` 覆盖，并对接企业 SSO / LDAP。

### 5. 调用示例

```bash
# 登录拿 token
TOKEN=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"hr001","password":"demo123"}' | jq -r .data)

# 问答（同步）
curl -X POST http://localhost:8080/api/v1/rag/query \
  -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" \
  -d '{"question":"年假怎么申请？","kbIds":[1]}'

# 问答（SSE 流式，-N 关闭缓冲才能看到实时流）
curl -N "http://localhost:8080/api/v1/chat/stream?question=年假怎么申请&kbIds=1" \
  -H "Authorization: Bearer $TOKEN"
```

## API 一览

| 模块 | 端点 |
|---|---|
| 认证 | `POST /api/v1/auth/login`、`POST /api/v1/auth/logout` |
| 知识库 | `GET /api/v1/kb`、`POST /api/v1/kb` |
| 文档 | `POST /api/v1/kb/{kbId}/documents`（上传）、`GET .../documents`（列表）、`GET .../documents/{docId}/status`（索引状态）、`GET .../documents/{docId}/download`、`DELETE .../documents/{docId}`、`PUT .../documents/{docId}/content`（整份替换）、`POST .../documents/{docId}/reindex` 与 `/reindex-force`（重建索引） |
| 问答 | `POST /api/v1/rag/query`（同步）、`GET /api/v1/chat/stream`（SSE 流式）、`POST /api/v1/chat`（同步+会话） |
| 会话 | `GET /api/v1/chat/sessions`、`GET /api/v1/chat/sessions/{sessionId}/messages` |
| 评估 | `POST /api/v1/eval/{kbId}/run`（跑评估，仅管理员）、`GET /api/v1/eval/{kbId}/history`（版本对比）、`GET|POST|PUT|DELETE /api/v1/eval/{kbId}/dataset[/{id}]`（数据集维护）、`GET /api/v1/eval/{kbId}/chunks`（标注用分块浏览） |
| 反馈 | `POST /api/v1/feedback/{messageId}?feedback=1\|-1` |
| 统计 | `GET /api/v1/stats/tokens` |

除 `/api/v1/auth/**` 与 `/actuator/**` 外，全部需要 `Authorization: Bearer <token>`。

**响应约定**：所有接口返回统一信封 `{ code, message, data }`；业务异常按语义返回 400 / 403 / 404，未预期异常统一 500 且不外泄堆栈。

**SSE 事件协议**：`status`（阶段通知）、`token`（增量文本，JSON 包裹 `{"t":"片段"}`）、`done`（完成，携带 `sources`）、`error`（失败）。

## 数据库

| 表 | 用途 |
|---|---|
| `users` | 用户（演示版，生产对接 SSO） |
| `kb_knowledge_base` | 知识库 |
| `kb_permission` | 知识库授权（主体类型 × 权限级别） |
| `kb_document` | 文档元数据与版本号 |
| `kb_doc_chunk` | 分块正文 + 1024 维向量 + 版本号 |
| `kb_index_task` | 异步索引任务与重试计数 |
| `kb_chat_session` / `kb_chat_message` | 会话与消息（消息带引用来源 JSON） |
| `kb_answer_feedback` | 点赞 / 点踩明细 |
| `kb_eval_dataset` / `kb_eval_result` | 评估数据集与执行结果 |

表结构语义以 `src/main/resources/db/schema.sql` 末尾的注释区为准。

## 目录结构

```
src/main/java/com/kyle/rag
├── RagApplication.java          # 启动类（.env 加载入口，@EnableAsync/@EnableRetry/@EnableCaching）
├── common/AuthConstants.java    # 角色与权限级别常量
├── config/                      # AsyncConfig 双线程池 / RedisConfig / MinioConfig / SpringAiConfig
│                                # WebMvcConfig / GlobalExceptionHandler / DataInitializer
├── controller/                  # 8 个 REST 入口：Auth/KnowledgeBase/DocumentUpdate/RagQuery
│                                # Chat/Eval/Feedback/Stats
├── dto/                         # 请求与响应体（ApiResponse 统一信封）
├── entity/                      # 11 张表的实体
├── exception/BizException.java  # 业务异常（携带 HTTP 语义 code）
├── repository/                  # Spring Data JPA 仓库
├── security/                    # SecurityConfig(Sa-Token 过滤)/ KbAuthInterceptor / UserContext / TraceFilter
└── service/                     # 业务服务
    ├── loader/                  # 文档解析：DocumentParser SPI + PDF/DOCX/MD/TXT 实现
    └── splitter/                # 分块：ChunkSplitter SPI + 滑动窗口 / 结构感知实现
```

## 开发约定

- **软删优先**：`@SoftDelete` 实体由 Hibernate 自动置位与过滤；分块数据量大且可重建，走物理删除
- **无外键**：引用完整性由应用层保证，实体间用裸 id 关联
- **权限唯一事实源**：可读判定只在 `PermissionService.isReadable`，其他地方不要重写规则
- **管道唯一实现**：检索链路改动只动 `FullRagPipeline.prepare()`，不要在各 Controller 里复制管道
- **枚举状态存库用英文大写**；表结构含义以 `db/schema.sql` 注释区为准
- **异常用 `BizException`**（携带 4xx 语义），不要抛裸 `RuntimeException`
- **`.env` 绝不入库**；协作者从 `.env.example` 复制后填值
- **注释只描述现状**：不写待办、不写「以后要改成…」

## 测试

```bash
mvn test
```

38 个用例覆盖解析、切块、索引与 dotenv 加载；其中集成测试需要可访问的 PostgreSQL / Redis / MinIO。
