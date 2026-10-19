-- ================================================================
-- jc-rag-kb 数据库初始化脚本
-- 执行前确保已创建数据库：CREATE DATABASE ragkb;
-- 所有表/字段注释统一放在文件末尾的「注释区」，改注释去那里改
-- ================================================================

-- 启用 PGVector 扩展（必须，向量存储依赖）
CREATE EXTENSION IF NOT EXISTS vector;

-- ================================================================
-- 1. 知识库表
-- ================================================================
CREATE TABLE kb_knowledge_base (
                                   id              BIGSERIAL PRIMARY KEY,
                                   name            VARCHAR(100)    NOT NULL,
                                   description     TEXT,
                                   department_id   VARCHAR(50)     NOT NULL,           -- 归属部门
                                   is_public       BOOLEAN         NOT NULL DEFAULT FALSE, -- 是否对所有人开放
                                   created_by      BIGINT          NOT NULL,           -- 创建者 userId
                                   created_at      TIMESTAMP       NOT NULL DEFAULT NOW(),
                                   updated_at      TIMESTAMP       NOT NULL DEFAULT NOW(),
                                   is_deleted      BOOLEAN         NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_kb_department ON kb_knowledge_base(department_id) WHERE is_deleted = FALSE;

-- ================================================================
-- 2. 知识库权限表
-- ================================================================
CREATE TABLE kb_permission (
                               id              BIGSERIAL PRIMARY KEY,
                               kb_id           BIGINT          NOT NULL,
                               subject_type    VARCHAR(20)     NOT NULL,           -- DEPARTMENT / USER
                               subject_id      VARCHAR(50)     NOT NULL,           -- 部门ID 或 userId
                               permission      VARCHAR(20)     NOT NULL,           -- READ / WRITE / ADMIN
                               granted_by      BIGINT          NOT NULL,
                               granted_at      TIMESTAMP       NOT NULL DEFAULT NOW(),
                               UNIQUE (kb_id, subject_type, subject_id)
);

CREATE INDEX idx_permission_subject ON kb_permission(subject_type, subject_id);

-- ================================================================
-- 3. 文档表
-- ================================================================
CREATE TABLE kb_document (
                             id              BIGSERIAL PRIMARY KEY,
                             kb_id           BIGINT          NOT NULL,
                             file_name       VARCHAR(255)    NOT NULL,
                             file_type       VARCHAR(20)     NOT NULL,           -- PDF / DOCX / MD / TXT
                             file_size       BIGINT          NOT NULL,           -- 字节数
                             minio_path      VARCHAR(500)    NOT NULL,           -- MinIO 中的对象路径
                             status          VARCHAR(20)     NOT NULL DEFAULT 'PENDING',
    -- PENDING / PROCESSING / DONE / FAILED
                             error_msg       TEXT,                              -- 失败原因
                             chunk_count     INT             DEFAULT 0,          -- 索引后的分块数量
                             token_count     INT             DEFAULT 0,          -- 向量化消耗的 Token 数
                             version         INT             NOT NULL DEFAULT 1, -- 文档版本号，更新时递增
                             uploaded_by     BIGINT          NOT NULL,
                             uploaded_at     TIMESTAMP       NOT NULL DEFAULT NOW(),
                             indexed_at      TIMESTAMP,                         -- 最近一次索引完成时间
                             is_deleted      BOOLEAN         NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_doc_kb_id ON kb_document(kb_id) WHERE is_deleted = FALSE;
CREATE INDEX idx_doc_status ON kb_document(status) WHERE is_deleted = FALSE;

-- ================================================================
-- 4. 文档分块表（核心表，含向量字段）
-- ================================================================
CREATE TABLE kb_doc_chunk (
                              id              BIGSERIAL PRIMARY KEY,
                              doc_id          BIGINT          NOT NULL,
                              kb_id           BIGINT          NOT NULL,           -- 冗余存储，检索时避免 JOIN
                              chunk_index     INT             NOT NULL,           -- 在文档中的顺序（0-based）
                              content         TEXT            NOT NULL,           -- 分块原文
                              content_tsv     TSVECTOR,                          -- 全文检索索引（自动维护）
                              embedding       VECTOR(1024)    NOT NULL,           -- 向量（1024 维，配 text-embedding-v4）
                              page_num        INT,                               -- 来自文档第几页（PDF 专用）
                              section_title   VARCHAR(500),                      -- 所在章节标题（如果能识别）
                              token_count     INT             NOT NULL DEFAULT 0, -- 该块的 Token 估算数
                              doc_version     INT             NOT NULL,           -- 对应的文档版本号
                              created_at      TIMESTAMP       NOT NULL DEFAULT NOW()
);

-- 向量检索索引（HNSW，适合高并发检索）
-- m=16: 每个节点的最大连接数，越大越准但更占内存
-- ef_construction=128: 构建索引时的搜索宽度，越大越准但建索引更慢
CREATE INDEX idx_chunk_embedding ON kb_doc_chunk
    USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 128);

-- 全文检索索引
CREATE INDEX idx_chunk_content_tsv ON kb_doc_chunk USING GIN (content_tsv);

-- 按 kb_id 过滤的索引（多租户场景必须有）
CREATE INDEX idx_chunk_kb_id ON kb_doc_chunk(kb_id);
CREATE INDEX idx_chunk_doc_id ON kb_doc_chunk(doc_id);

-- 触发器：自动维护全文检索向量
-- 简单版：用默认英文分词（中文效果一般，但不需要额外扩展）
-- 注意：中文全文检索效果不佳，主要靠向量检索；全文检索作为补充用于精确词搜索
CREATE OR REPLACE FUNCTION update_chunk_tsv()
RETURNS TRIGGER AS $$
BEGIN
    NEW.content_tsv := to_tsvector('simple', NEW.content);
RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trigger_chunk_tsv
    BEFORE INSERT OR UPDATE OF content
                     ON kb_doc_chunk
                         FOR EACH ROW
                         EXECUTE FUNCTION update_chunk_tsv();

-- ================================================================
-- 5. 索引任务表（异步索引状态管理）
-- ================================================================
CREATE TABLE kb_index_task (
                               id              BIGSERIAL PRIMARY KEY,
                               doc_id          BIGINT          NOT NULL,
                               task_type       VARCHAR(20)     NOT NULL DEFAULT 'INDEX',  -- INDEX / REINDEX
                               status          VARCHAR(20)     NOT NULL DEFAULT 'PENDING',
                               retry_count     INT             NOT NULL DEFAULT 0,
                               max_retry       INT             NOT NULL DEFAULT 3,
                               error_msg       TEXT,
                               created_at      TIMESTAMP       NOT NULL DEFAULT NOW(),
                               started_at      TIMESTAMP,
                               finished_at     TIMESTAMP
);

CREATE INDEX idx_task_status ON kb_index_task(status, created_at);
CREATE INDEX idx_task_doc_id ON kb_index_task(doc_id);

-- ================================================================
-- 6. 对话会话表
-- ================================================================
CREATE TABLE kb_chat_session (
                                 id              VARCHAR(36)     PRIMARY KEY,        -- UUID
                                 user_id         BIGINT          NOT NULL,
                                 kb_ids          TEXT            NOT NULL,           -- JSON 数组，查询的知识库列表
                                 title           VARCHAR(200),                      -- 会话标题（取第一条消息）
                                 message_count   INT             NOT NULL DEFAULT 0,
                                 created_at      TIMESTAMP       NOT NULL DEFAULT NOW(),
                                 last_active_at  TIMESTAMP       NOT NULL DEFAULT NOW(),
                                 is_deleted      BOOLEAN         NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_session_user ON kb_chat_session(user_id, last_active_at DESC)
    WHERE is_deleted = FALSE;

-- ================================================================
-- 7. 对话消息表
-- ================================================================
CREATE TABLE kb_chat_message (
                                 id              BIGSERIAL PRIMARY KEY,
                                 session_id      VARCHAR(36)     NOT NULL,
                                 role            VARCHAR(20)     NOT NULL,           -- USER / ASSISTANT
                                 content         TEXT            NOT NULL,
                                 sources         JSONB,                             -- 引用来源列表（仅 ASSISTANT 消息有）
                                 token_count     INT             DEFAULT 0,          -- 消耗的 Token 数
                                 latency_ms      INT             DEFAULT 0,          -- 生成耗时（毫秒）
                                 feedback        SMALLINT,                          -- 用户反馈：1=好 -1=差 NULL=未反馈
                                 created_at      TIMESTAMP       NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_message_session ON kb_chat_message(session_id, created_at);

-- ================================================================
-- 8. 用户反馈表（点赞/点踩，用于效果评估）
-- ================================================================
CREATE TABLE kb_answer_feedback (
                                    id              BIGSERIAL PRIMARY KEY,
                                    message_id      BIGINT          NOT NULL,
                                    user_id         BIGINT          NOT NULL,
                                    feedback        SMALLINT        NOT NULL,           -- 1=有用 -1=无用
                                    comment         TEXT,                              -- 可选的文字反馈
                                    created_at      TIMESTAMP       NOT NULL DEFAULT NOW(),
                                    UNIQUE (message_id, user_id)
);

-- ================================================================
-- 9. RAG 评估数据集表
-- ================================================================
CREATE TABLE kb_eval_dataset (
                                 id              BIGSERIAL PRIMARY KEY,
                                 kb_id           BIGINT          NOT NULL,
                                 question        TEXT            NOT NULL,
                                 expected_answer TEXT,
                                 expected_chunk_ids  BIGINT[],                      -- 期望召回的 chunk ID
                                 created_by      BIGINT          NOT NULL,
                                 created_at      TIMESTAMP       NOT NULL DEFAULT NOW()
);

CREATE TABLE kb_eval_result (
                                id              BIGSERIAL PRIMARY KEY,
                                dataset_id      BIGINT          NOT NULL,
                                eval_version    VARCHAR(50)     NOT NULL,           -- 评估版本（如：v1_chunk512_hybrid）
                                hit             BOOLEAN         NOT NULL,           -- 是否命中（期望 chunk 在召回结果中）
                                rank            INT,                               -- 命中 chunk 的排名（用于 MRR 计算）
                                actual_answer   TEXT,
                                faithfulness    FLOAT,                             -- RAGAS Faithfulness 分数
                                answer_relevancy FLOAT,                            -- RAGAS Answer Relevancy 分数
                                eval_at         TIMESTAMP       NOT NULL DEFAULT NOW()
);

-- ================================================================
-- 10. 注释区（表 + 字段注释统一维护处，COMMENT ON 可重复执行）
-- ================================================================

-- ---- 1. kb_knowledge_base 知识库 ----
COMMENT ON TABLE kb_knowledge_base            IS '知识库，一个部门可以有多个知识库';
COMMENT ON COLUMN kb_knowledge_base.id        IS '主键';
COMMENT ON COLUMN kb_knowledge_base.name      IS '知识库名称';
COMMENT ON COLUMN kb_knowledge_base.description IS '知识库描述';
COMMENT ON COLUMN kb_knowledge_base.department_id IS '归属部门ID';
COMMENT ON COLUMN kb_knowledge_base.is_public IS '是否对所有人开放；false 时按 kb_permission 授权访问';
COMMENT ON COLUMN kb_knowledge_base.created_by IS '创建者 userId';
COMMENT ON COLUMN kb_knowledge_base.created_at IS '创建时间';
COMMENT ON COLUMN kb_knowledge_base.updated_at IS '更新时间（应用层维护）';
COMMENT ON COLUMN kb_knowledge_base.is_deleted IS '软删除标记';

-- ---- 2. kb_permission 知识库授权 ----
COMMENT ON TABLE kb_permission            IS '知识库授权表：部门/用户对知识库的访问权限';
COMMENT ON COLUMN kb_permission.id        IS '主键';
COMMENT ON COLUMN kb_permission.kb_id     IS '知识库ID';
COMMENT ON COLUMN kb_permission.subject_type IS '授权主体类型：DEPARTMENT / USER';
COMMENT ON COLUMN kb_permission.subject_id IS '部门ID 或 userId（随 subject_type）';
COMMENT ON COLUMN kb_permission.permission IS '权限级别：READ / WRITE / ADMIN';
COMMENT ON COLUMN kb_permission.granted_by IS '授权人 userId';
COMMENT ON COLUMN kb_permission.granted_at IS '授权时间';

-- ---- 3. kb_document 文档 ----
COMMENT ON TABLE kb_document            IS '上传到知识库的文档，一个文档对应多个分块';
COMMENT ON COLUMN kb_document.id        IS '主键';
COMMENT ON COLUMN kb_document.kb_id     IS '所属知识库ID';
COMMENT ON COLUMN kb_document.file_name IS '原始文件名';
COMMENT ON COLUMN kb_document.file_type IS '文件类型：PDF / DOCX / MD / TXT';
COMMENT ON COLUMN kb_document.file_size IS '文件字节数';
COMMENT ON COLUMN kb_document.minio_path IS 'MinIO 中的对象路径';
COMMENT ON COLUMN kb_document.status    IS '索引状态：PENDING 待处理 / PROCESSING 处理中 / DONE 完成 / FAILED 失败';
COMMENT ON COLUMN kb_document.error_msg IS '索引失败原因';
COMMENT ON COLUMN kb_document.chunk_count IS '当前版本的分块数量';
COMMENT ON COLUMN kb_document.token_count IS '向量化消耗的 Token 总数';
COMMENT ON COLUMN kb_document.version   IS '版本号，重建索引 +1；旧版本分块按版本号识别并删除';
COMMENT ON COLUMN kb_document.uploaded_by IS '上传者 userId';
COMMENT ON COLUMN kb_document.uploaded_at IS '上传时间';
COMMENT ON COLUMN kb_document.indexed_at IS '最近一次索引完成时间';
COMMENT ON COLUMN kb_document.is_deleted IS '软删除标记';

-- ---- 4. kb_doc_chunk 文档分块 ----
COMMENT ON TABLE kb_doc_chunk            IS '文档分块表，每条记录是一个可检索的最小单元';
COMMENT ON COLUMN kb_doc_chunk.id        IS '主键';
COMMENT ON COLUMN kb_doc_chunk.doc_id    IS '所属文档ID';
COMMENT ON COLUMN kb_doc_chunk.kb_id     IS '所属知识库ID（冗余，检索免 JOIN）';
COMMENT ON COLUMN kb_doc_chunk.chunk_index IS '在文档中的顺序（0-based）';
COMMENT ON COLUMN kb_doc_chunk.content   IS '分块原文';
COMMENT ON COLUMN kb_doc_chunk.content_tsv IS '全文检索向量，触发器自动维护';
COMMENT ON COLUMN kb_doc_chunk.embedding IS '语义向量，text-embedding-v4 1024 维';
COMMENT ON COLUMN kb_doc_chunk.page_num  IS '来自文档第几页（PDF 专用）';
COMMENT ON COLUMN kb_doc_chunk.section_title IS '所在章节标题（能识别时）';
COMMENT ON COLUMN kb_doc_chunk.token_count IS '该块的 Token 估算数';
COMMENT ON COLUMN kb_doc_chunk.doc_version IS '冗余版本号，重建索引后删除旧版本分块用';
COMMENT ON COLUMN kb_doc_chunk.created_at IS '创建时间';

-- ---- 5. kb_index_task 索引任务 ----
COMMENT ON TABLE kb_index_task            IS '索引任务表：异步索引的状态与重试管理';
COMMENT ON COLUMN kb_index_task.id        IS '主键';
COMMENT ON COLUMN kb_index_task.doc_id    IS '文档ID';
COMMENT ON COLUMN kb_index_task.task_type IS '任务类型：INDEX 首次索引 / REINDEX 重建';
COMMENT ON COLUMN kb_index_task.status    IS '任务状态：PENDING / RUNNING / DONE / FAILED';
COMMENT ON COLUMN kb_index_task.retry_count IS '已重试次数';
COMMENT ON COLUMN kb_index_task.max_retry IS '最大重试次数';
COMMENT ON COLUMN kb_index_task.error_msg IS '失败原因';
COMMENT ON COLUMN kb_index_task.created_at IS '入队时间';
COMMENT ON COLUMN kb_index_task.started_at IS '开始执行时间';
COMMENT ON COLUMN kb_index_task.finished_at IS '结束时间';

-- ---- 6. kb_chat_session 对话会话 ----
COMMENT ON TABLE kb_chat_session            IS '对话会话：一个用户可有多个会话';
COMMENT ON COLUMN kb_chat_session.id        IS '会话ID（UUID）';
COMMENT ON COLUMN kb_chat_session.user_id   IS '所属用户 userId';
COMMENT ON COLUMN kb_chat_session.kb_ids    IS '会话关联的知识库ID列表（JSON 数组）';
COMMENT ON COLUMN kb_chat_session.title     IS '会话标题（默认取首条消息）';
COMMENT ON COLUMN kb_chat_session.message_count IS '会话内消息数量';
COMMENT ON COLUMN kb_chat_session.created_at IS '创建时间';
COMMENT ON COLUMN kb_chat_session.last_active_at IS '最近活跃时间';
COMMENT ON COLUMN kb_chat_session.is_deleted IS '软删除标记';

-- ---- 7. kb_chat_message 对话消息 ----
COMMENT ON TABLE kb_chat_message            IS '对话消息：属于某个会话';
COMMENT ON COLUMN kb_chat_message.id        IS '主键';
COMMENT ON COLUMN kb_chat_message.session_id IS '所属会话ID';
COMMENT ON COLUMN kb_chat_message.role      IS '角色：USER / ASSISTANT';
COMMENT ON COLUMN kb_chat_message.content   IS '消息内容';
COMMENT ON COLUMN kb_chat_message.sources   IS '引用来源 JSON 数组：[{"docId":1,"docName":"手册.pdf","chunkId":100,"pageNum":5,"excerpt":"...","score":0.92}]（仅 ASSISTANT 消息有）';
COMMENT ON COLUMN kb_chat_message.token_count IS '该消息消耗的 Token 数';
COMMENT ON COLUMN kb_chat_message.latency_ms IS '生成耗时（毫秒）';
COMMENT ON COLUMN kb_chat_message.feedback  IS '冗余反馈缓存：1=好 -1=差 NULL=未反馈（明细以 kb_answer_feedback 为准）';
COMMENT ON COLUMN kb_chat_message.created_at IS '创建时间';

-- ---- 8. kb_answer_feedback 回答反馈 ----
COMMENT ON TABLE kb_answer_feedback            IS '回答反馈明细表：一人对一消息最多一条';
COMMENT ON COLUMN kb_answer_feedback.id        IS '主键';
COMMENT ON COLUMN kb_answer_feedback.message_id IS '消息ID';
COMMENT ON COLUMN kb_answer_feedback.user_id   IS '反馈用户 userId';
COMMENT ON COLUMN kb_answer_feedback.feedback  IS '反馈：1=有用 -1=无用';
COMMENT ON COLUMN kb_answer_feedback.comment   IS '可选的文字反馈';
COMMENT ON COLUMN kb_answer_feedback.created_at IS '反馈时间';

-- ---- 9. kb_eval_dataset / kb_eval_result RAG 评估 ----
COMMENT ON TABLE kb_eval_dataset            IS 'RAG 评估数据集：标准问答对';
COMMENT ON COLUMN kb_eval_dataset.id        IS '主键';
COMMENT ON COLUMN kb_eval_dataset.kb_id     IS '针对的知识库ID';
COMMENT ON COLUMN kb_eval_dataset.question  IS '测试问题';
COMMENT ON COLUMN kb_eval_dataset.expected_answer IS '期望答案';
COMMENT ON COLUMN kb_eval_dataset.expected_chunk_ids IS '期望召回的 chunk ID 数组';
COMMENT ON COLUMN kb_eval_dataset.created_by IS '创建人 userId';
COMMENT ON COLUMN kb_eval_dataset.created_at IS '创建时间';
COMMENT ON TABLE kb_eval_result            IS '评估执行结果：命中率 / MRR / RAGAS 指标';
COMMENT ON COLUMN kb_eval_result.id        IS '主键';
COMMENT ON COLUMN kb_eval_result.dataset_id IS '数据集条目ID';
COMMENT ON COLUMN kb_eval_result.eval_version IS '评估版本标识（如 v1_chunk512_hybrid），用于对比不同配置';
COMMENT ON COLUMN kb_eval_result.hit       IS '期望 chunk 是否命中召回结果';
COMMENT ON COLUMN kb_eval_result.rank      IS '命中 chunk 的排名（MRR 计算用）';
COMMENT ON COLUMN kb_eval_result.actual_answer IS '实际生成的答案';
COMMENT ON COLUMN kb_eval_result.faithfulness IS 'RAGAS 忠实度分数';
COMMENT ON COLUMN kb_eval_result.answer_relevancy IS 'RAGAS 答案相关度分数';
COMMENT ON COLUMN kb_eval_result.eval_at   IS '评估时间';
