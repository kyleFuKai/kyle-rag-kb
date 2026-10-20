-- ================================================================
-- RAG 知识库测试数据
-- ================================================================
-- 使用方法：先执行 schema.sql，再连接目标测试库（项目默认 ragkb），
-- 在同一个数据库会话中完整执行本文件；本文件不会随应用启动自动执行。
-- 全文采用普通 SQL，不使用 DO 代码块或美元引号，便于客户端按分号执行。
-- 如果之前执行失败使事务中止，先在原会话单独执行 ROLLBACK，再执行本文件。
--
-- 数据范围：4 个知识库、5 条部门权限、5 条评估问题。
-- 项目没有本地用户表：1 / 2 / 3 仅作为 HR / TECH / PROD 的模拟用户 ID。
-- 以下制度、配额与答案沿用演示样例，可按实际业务替换。
--
-- 重复执行规则：
-- 1. 按“名称 + 部门”复用未删除的知识库，不依赖主键从 1 开始。
-- 2. 匹配到多个未删除的知识库时直接报错，避免错误关联。
-- 3. 已有知识库、权限和同知识库下的同名问题不覆盖；已删除知识库不恢复。
-- 4. 不插入文档或模拟向量，expected_chunk_ids 等真实文档完成索引后回填。
-- 请勿并发执行本文件。

BEGIN;

-- ================================================================
-- 1. 知识库初始化：不存在时新增，已有记录保持原值
-- ================================================================
-- 标量子查询没有匹配时返回 NULL；匹配多条时直接报错，避免错误关联。
WITH seed(name, description, department_id, is_public, created_by) AS (
    VALUES
        ('HR知识库',       '公司人事制度、员工手册、入职离职流程等文档',   'HR',   FALSE, 1),
        ('技术知识库',     '技术规范、架构设计文档、开发指南等',           'TECH', FALSE, 2),
        ('产品知识库',     '产品手册、功能说明、FAQ等面向内部的产品文档',  'PROD', TRUE,  3),
        ('公司公共知识库', '公司介绍、组织架构、通用制度等所有人可查的文档', 'ALL', TRUE,  1)
)
INSERT INTO public.kb_knowledge_base
    (name, description, department_id, is_public, created_by)
SELECT name, description, department_id, is_public, created_by
FROM seed s
WHERE (
    SELECT k.id
    FROM public.kb_knowledge_base k
    WHERE k.name = s.name
      AND k.department_id = s.department_id
      AND k.is_deleted = FALSE
) IS NULL;

-- ================================================================
-- 2. 部门权限：动态查询知识库 ID，不依赖自增 ID 从 1 开始
-- ================================================================
-- HR 知识库：HR 可写，TECH / PROD 可读。
-- 技术知识库：TECH 可写，PROD 可读。
-- 产品知识库、公司公共知识库为公开知识库，不额外插入部门授权。
-- 已有授权保持原值；如需调整，单独修改对应权限记录。
WITH seed(kb_name, department_id, subject_type, subject_id, permission, granted_by) AS (
    VALUES
        ('HR知识库',   'HR',   'DEPARTMENT', 'HR',   'WRITE', 1),
        ('HR知识库',   'HR',   'DEPARTMENT', 'TECH', 'READ',  1),
        ('HR知识库',   'HR',   'DEPARTMENT', 'PROD', 'READ',  1),
        ('技术知识库', 'TECH', 'DEPARTMENT', 'TECH', 'WRITE', 2),
        ('技术知识库', 'TECH', 'DEPARTMENT', 'PROD', 'READ',  2)
)
INSERT INTO public.kb_permission
    (kb_id, subject_type, subject_id, permission, granted_by)
SELECT
    (
        SELECT k.id
        FROM public.kb_knowledge_base k
        WHERE k.name = s.kb_name
          AND k.department_id = s.department_id
          AND k.is_deleted = FALSE
    ),
    s.subject_type, s.subject_id, s.permission, s.granted_by
FROM seed s
ON CONFLICT (kb_id, subject_type, subject_id) DO NOTHING;

-- ================================================================
-- 3. 评估数据集：初始不指定分块 ID
-- ================================================================
-- 按“知识库 ID + 问题原文”查重；重复执行不会清空已回填的分块 ID。
WITH seed(kb_name, department_id, question, expected_answer, created_by) AS (
    VALUES
        ('HR知识库', 'HR',
         '新员工入职第一天需要做什么？',
         '领取工牌和电脑，配置 VPN 和开发环境，与直属 Leader 完成对齐会，阅读代码规范。',
         1),
        ('HR知识库', 'HR',
         '年假是怎么规定的？',
         '工作满 1 年未满 10 年享有 5 天年假，工作满 10 年以上享有 10 天年假。',
         1),
        ('技术知识库', 'TECH',
         'API 限流策略是什么？',
         '单用户每分钟最多 100 次调用，采用滑动窗口算法，超出后返回 429 状态码。',
         2),
        ('技术知识库', 'TECH',
         '代码提交规范有哪些？',
         'Commit message 格式为 type(scope): message，type 包括 feat/fix/docs/refactor。',
         2),
        ('产品知识库', 'PROD',
         '如何申请 API 访问权限？',
         '在开发者控制台创建 API Key，免费配额为每日 1000 次调用，付费套餐按量计费。',
         3)
), resolved AS (
    SELECT
        (
            SELECT k.id
            FROM public.kb_knowledge_base k
            WHERE k.name = s.kb_name
              AND k.department_id = s.department_id
              AND k.is_deleted = FALSE
        ) AS kb_id,
        s.question, s.expected_answer, s.created_by
    FROM seed s
)
INSERT INTO public.kb_eval_dataset
    (kb_id, question, expected_answer, expected_chunk_ids, created_by)
SELECT r.kb_id, r.question, r.expected_answer, NULL::BIGINT[], r.created_by
FROM resolved r
WHERE NOT EXISTS (
    SELECT 1
    FROM public.kb_eval_dataset e
    WHERE e.kb_id = r.kb_id
      AND e.question = r.question
);

COMMIT;

-- ================================================================
-- 4. 查看结果：全新测试库应返回 4 行
--    权限数量依次为 3 / 2 / 0 / 0，评估问题数量依次为 2 / 2 / 1 / 0。
--    如果复用了已有知识库，数量还会包含其中原有的数据。
-- ================================================================
WITH seed(name, department_id, display_order) AS (
    VALUES
        ('HR知识库',       'HR',   1),
        ('技术知识库',     'TECH', 2),
        ('产品知识库',     'PROD', 3),
        ('公司公共知识库', 'ALL',  4)
)
SELECT
    k.id AS kb_id,
    k.name,
    k.department_id,
    k.is_public,
    (SELECT COUNT(*) FROM public.kb_permission p WHERE p.kb_id = k.id) AS permission_count,
    (SELECT COUNT(*) FROM public.kb_eval_dataset e WHERE e.kb_id = k.id) AS eval_question_count
FROM public.kb_knowledge_base k
JOIN seed s ON s.name = k.name AND s.department_id = k.department_id
WHERE k.is_deleted = FALSE
ORDER BY s.display_order;

-- ================================================================
-- 5. 真实文档上传并完成索引后的操作示例（默认不执行）
-- ================================================================
-- 先查看评估问题的实际 ID：
-- SELECT e.id AS dataset_id, k.id AS kb_id, k.name, e.question, e.expected_chunk_ids
-- FROM public.kb_eval_dataset e
-- JOIN public.kb_knowledge_base k ON k.id = e.kb_id
-- WHERE k.is_deleted = FALSE
-- ORDER BY k.id, e.id;
--
-- 再查看未删除文档的当前版本分块，人工判断哪些块能够回答对应问题：
-- SELECT c.id AS chunk_id, c.kb_id, c.doc_id, c.doc_version, LEFT(c.content, 100) AS preview
-- FROM public.kb_doc_chunk c
-- JOIN public.kb_document d ON d.id = c.doc_id AND d.kb_id = c.kb_id
-- JOIN public.kb_knowledge_base k ON k.id = c.kb_id
-- WHERE k.is_deleted = FALSE
--   AND d.is_deleted = FALSE
--   AND c.doc_version = d.version
-- ORDER BY c.kb_id, c.doc_id, c.chunk_index;
--
-- 确认 chunk 属于该评估问题的知识库、当前文档版本且内容相关后，
-- 将以下占位符替换成查询到的实际 ID，再取消注释执行：
-- UPDATE public.kb_eval_dataset
-- SET expected_chunk_ids = ARRAY[<chunk_id_1>, <chunk_id_2>]::BIGINT[]
-- WHERE id = <dataset_id>;
