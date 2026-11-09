package com.kyle.rag.repository;

import com.kyle.rag.entity.EvalDataset;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
/**
 *
 *
 * @author kyle
 * @version 1.0
 * @date 2026/11/9 23:49
 */
public interface EvalDatasetRepository extends JpaRepository<EvalDataset, Long> {


    List<EvalDataset> findByKbId(Long kbId);
}
