# kyle-rag-kb

基于 Spring AI 的 RAG 知识库服务：文档上传 → 解析切分 → 向量化入库 → 混合检索（向量 + 全文 + 重排）→ 大模型生成回答。

## 技术栈

- Java 21 / Spring Boot 3.5.11 / Spring AI 1.1.2
- 大模型：MiniMax（对话）｜ 阿里百炼：向量化（text-embedding-v4，1024 维）+ 重排（qwen3-rerank）
- 存储：PostgreSQL + pgvector（业务数据 + 向量）、Redis（缓存 + 登录态）、MinIO（原始文件）
- 认证：Sa-Token（登录态存 Redis）｜ 监控：Actuator + Prometheus

## 快速开始

```bash
# 1. 克隆
git clone https://gitee.com/xingzhewk/kyle-rag-kb.git

# 2. 配置环境变量（敏感值全部走 .env，不入库）
copy .env.example .env    # Windows
cp .env.example .env      # Linux / Mac
# 填入：PostgreSQL / Redis / MinIO 的地址与密码、MINIMAX_API_KEY、DASHSCOPE_API_KEY

# 3. 初始化数据库（PostgreSQL 需已启用 pgvector 扩展）
#    在 ragkb 库中执行 db/schema.sql

# 4. 启动
mvn spring-boot:run
```

启动成功后服务在 http://localhost:8080 ，健康检查 `/actuator/health`。

## 目录结构

```
src/main/java/com/kyle/rag
├── RagApplication.java        # 启动类（.env 加载入口，@EnableAsync / @EnableRetry）
├── config/                    # AsyncConfig 双线程池 / RedisConfig / MinioConfig
├── security/UserContext.java  # 当前登录用户 ThreadLocal 上下文
└── entity/                    # KnowledgeBase / KbDocument / DocChunk / IndexTask
src/main/resources
├── application.yml            # 配置（敏感值全部走 .env 占位符）
└── db/schema.sql              # 建表脚本（表/字段注释统一在文末注释区）
```

## 开发约定

- 所有删除只软删：`@SoftDelete` 自动处理置位与查询过滤，代码里不写物理 DELETE
- 无外键：引用完整性由应用层保证；实体间用裸 id 关联
- 枚举状态存库用英文大写；表结构含义以 `db/schema.sql` 注释区为准
- `.env` 绝不入库；协作者从 `.env.example` 复制后填值
