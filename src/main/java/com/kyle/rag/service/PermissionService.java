package com.kyle.rag.service;

import com.kyle.rag.entity.KnowledgeBase;
import com.kyle.rag.exception.BizException;
import com.kyle.rag.repository.KbPermissionRepository;
import com.kyle.rag.repository.KnowledgeBaseRepository;
import com.kyle.rag.security.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import com.kyle.rag.common.AuthConstants;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
/**
 * 知识库访问权限的判定入口:Controller/Service 在读、写操作前调 requireRead / requireWrite,无权即抛异常。
 * 判定顺序:ADMIN 角色 → 公开库可读 → 用户授权 ∨ 部门授权(requireWrite 要求级别 ≥ WRITE)。
 * 不存在的 kbId 走同一句"无权访问",不向调用方泄露知识库是否存在。
 * 注意:可访问列表(KnowledgeBaseService.listAccessible)里还有一份权限合并逻辑,共用同一套语义,改规则要同步。
 *
 * @author kyle
 * @version 1.0
 * @date 2026/11/2 23:56
 */
@Service
@RequiredArgsConstructor
public class PermissionService {

    /** requireWrite 认可的最低级别:WRITE 及以上(ADMIN)都可管理文档 */
    private static final List<String> WRITE_LEVELS = List.of(AuthConstants.PERM_WRITE, AuthConstants.PERM_ADMIN);

    private final KbPermissionRepository permissionRepository;
    private final KnowledgeBaseRepository kbRepository;

    /** 检查当前用户对知识库是否有读权限 */
    public void requireRead(Long kbId) {
        if (!isReadable(kbId)) {
            throw BizException.forbidden("无权访问该知识库");
        }
    }

    /**
     * 单库可读判定(权限规则的唯一事实源):ADMIN → 公开库 → 用户授权 ∨ 部门授权。
     * requireRead 与 filterReadableKbIds 都走这里,避免规则改一处漏一处。
     */
    private boolean isReadable(Long kbId) {
        if (UserContext.isAdmin()) return true;

        // 知识库是公开的——注意不能用 ifPresent + return，lambda 里的 return 只退出 lambda 本身
        boolean isPublic = kbRepository.findById(kbId)
                .map(KnowledgeBase::getIsPublic)
                .orElse(false);
        if (isPublic) return true;

        String userId = String.valueOf(UserContext.getUserId());
        String deptId = UserContext.getDepartmentId();
        return permissionRepository.existsByKbIdAndSubjectTypeAndSubjectId(kbId, AuthConstants.SUBJECT_USER, userId)
                || permissionRepository.existsByKbIdAndSubjectTypeAndSubjectId(kbId, AuthConstants.SUBJECT_DEPARTMENT, deptId);
    }

    /**
     * 过滤出当前用户可读的知识库(保留请求顺序)。
     * 判定与 requireRead 同源:ADMIN 全通 → 公开库可读 → 用户/部门授权。
     * 用途:检索与查询缓存前先过滤——无权库不参与检索,且缓存键只含实际检索的库,
     * 避免无权限用户命中他人缓存、或不同库集合共享同一缓存键。
     */
    public List<Long> filterReadableKbIds(List<Long> kbIds) {
        if (kbIds == null || kbIds.isEmpty()) return List.of();
        if (UserContext.isAdmin()) return kbIds;

        // 批量取数:一次查回这些库的公开状态 + 当前主体的全部授权,内存判定。
        // 逐库查会产生 N+1(每库 3 次往返),而每次往返在本机经隧道约 50-90ms。
        Set<Long> publicKbIds = kbRepository.findByIdIn(kbIds).stream()
                .filter(KnowledgeBase::getIsPublic)
                .map(KnowledgeBase::getId)
                .collect(Collectors.toSet());

        String userId = String.valueOf(UserContext.getUserId());
        String deptId = UserContext.getDepartmentId();
        Set<Long> grantedKbIds = new HashSet<>();
        for (String subjectType : List.of(AuthConstants.SUBJECT_USER, AuthConstants.SUBJECT_DEPARTMENT)) {
            String subjectId = AuthConstants.SUBJECT_USER.equals(subjectType) ? userId : deptId;
            permissionRepository.findForSubjects(kbIds, subjectType, subjectId)
                    .forEach(p -> grantedKbIds.add(p.getKbId()));
        }

        return kbIds.stream()
                .filter(id -> publicKbIds.contains(id) || grantedKbIds.contains(id))
                .toList();
    }

    /** 检查当前用户对知识库是否有写权限 */
    public void requireWrite(Long kbId) {
        if (UserContext.isAdmin()) return;

        String userId = String.valueOf(UserContext.getUserId());
        String deptId = UserContext.getDepartmentId();

        boolean hasWritePermission = permissionRepository
                .existsByKbIdAndSubjectTypeAndSubjectIdAndPermissionIn(kbId, AuthConstants.SUBJECT_USER, userId, WRITE_LEVELS)
                || permissionRepository
                .existsByKbIdAndSubjectTypeAndSubjectIdAndPermissionIn(kbId, AuthConstants.SUBJECT_DEPARTMENT, deptId, WRITE_LEVELS);

        if (!hasWritePermission) {
            throw BizException.forbidden("无文档管理权限");
        }
    }
}
