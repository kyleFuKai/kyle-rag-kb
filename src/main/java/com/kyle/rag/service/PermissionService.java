package com.kyle.rag.service;

import com.kyle.rag.entity.KnowledgeBase;
import com.kyle.rag.exception.BizException;
import com.kyle.rag.repository.KbPermissionRepository;
import com.kyle.rag.repository.KnowledgeBaseRepository;
import com.kyle.rag.security.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
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
    private static final List<String> WRITE_LEVELS = List.of("WRITE", "ADMIN");

    private final KbPermissionRepository permissionRepository;
    private final KnowledgeBaseRepository kbRepository;

    /** 检查当前用户对知识库是否有读权限 */
    public void requireRead(Long kbId) {
        if (UserContext.isAdmin()) return;  // 管理员直接放行

        // 知识库是公开的——注意不能用 ifPresent + return，lambda 里的 return 只退出 lambda 本身
        boolean isPublic = kbRepository.findById(kbId)
                .map(KnowledgeBase::getIsPublic)
                .orElse(false);
        if (isPublic) return;

        // 检查用户或部门权限
        String userId = String.valueOf(UserContext.getUserId());
        String deptId = UserContext.getDepartmentId();

        boolean hasPermission = permissionRepository.existsByKbIdAndSubjectTypeAndSubjectId(
                kbId, "USER", userId)
                || permissionRepository.existsByKbIdAndSubjectTypeAndSubjectId(
                kbId, "DEPARTMENT", deptId);

        if (!hasPermission) {
            throw BizException.forbidden("无权访问该知识库");
        }
    }

    /** 检查当前用户对知识库是否有写权限 */
    public void requireWrite(Long kbId) {
        if (UserContext.isAdmin()) return;

        String userId = String.valueOf(UserContext.getUserId());
        String deptId = UserContext.getDepartmentId();

        boolean hasWritePermission = permissionRepository
                .existsByKbIdAndSubjectTypeAndSubjectIdAndPermissionIn(
                        kbId, "USER", userId, WRITE_LEVELS)
                || permissionRepository
                .existsByKbIdAndSubjectTypeAndSubjectIdAndPermissionIn(
                        kbId, "DEPARTMENT", deptId, WRITE_LEVELS);

        if (!hasWritePermission) {
            throw BizException.forbidden("无文档管理权限");
        }
    }
}
