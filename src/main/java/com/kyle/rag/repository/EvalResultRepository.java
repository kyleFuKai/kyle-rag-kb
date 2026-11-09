package com.kyle.rag.repository;

import com.kyle.rag.dto.EvalReport;
import com.kyle.rag.entity.EvalResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
/**
 * 评估结果仓库:JPQL 构造器表达式直接聚合出 EvalReport,
 * 全部指标在 SQL 层算完(命中数/命中率/MRR/忠实度均值/最近评估时间),一次查询出报告。
 *
 * @author kyle
 * @version 1.0
 * @date 2026/11/9 23:50
 */
public interface EvalResultRepository extends JpaRepository<EvalResult, Long>{

    /** 按 kbId 聚合各 evalVersion 的指标,按最近评估时间倒序(版本对比用) */
    @Query("""
                SELECT new com.kyle.rag.dto.EvalReport(
                d.kbId, r.evalVersion, COUNT(r), 
                SUM(CASE WHEN r.hit = true THEN 1 ELSE 0 END),
                AVG(CASE WHEN r.hit = true THEN 1.0 ELSE 0.0 END),
                AVG(CASE WHEN r.rank > 0 THEN 1.0 / r.rank ELSE 0.0 END),
                AVG(COALESCE(r.faithfulness, 0)),
                MAX(r.evalAt))
            FROM EvalResult r JOIN EvalDataset d ON r.datasetId = d.id
            WHERE d.kbId = :kbId
            GROUP BY d.kbId, r.evalVersion
            ORDER BY MAX(r.evalAt) DESC
            """)
    List<EvalReport> aggregateByVersion(Long kbId);
}
