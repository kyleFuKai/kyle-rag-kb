package com.kyle.rag.repository;

import com.kyle.rag.entity.KbPermission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
/**
 * 知识库授权仓库。只服务私有知识库的访问判定;表无软删除,撤销授权即物理删除。
 * subjectId 统一是字符串:部门传部门 ID,用户传 String.valueOf(userId)。
 *
 * @author kyle
 * @version 1.0
 * @date 2026/11/2 13:00
 */
public interface KbPermissionRepository extends JpaRepository<KbPermission, Long> {

    /** 主体(用户或部门)拥有的全部授权——"我能看哪些知识库"用 */
    List<KbPermission> findBySubjectTypeAndSubjectId(String subjectType, String subjectId);

    /** 主体对该知识库是否有任何授权(不区分级别) */
    boolean existsByKbIdAndSubjectTypeAndSubjectId(
            Long kbId, String subjectType, String subjectId);

    /** 主体对该知识库是否持有给定级别之一——校验"至少 WRITE"时传 [WRITE, ADMIN] */
    boolean existsByKbIdAndSubjectTypeAndSubjectIdAndPermissionIn(
            Long kbId, String subjectType, String subjectId, List<String> permissions);

    /** 知识库的全部授权记录——授权管理页用 */
    List<KbPermission> findByKbId(Long kbId);

    /**
     * 删除知识库的全部授权(删知识库时清理)。
     * 派生 deleteBy 是"查出来逐条 remove",仓库方法默认跑在只读事务里会静默不落库,必须显式开写事务。
     */
    @Transactional
    void deleteByKbId(Long kbId);
}
