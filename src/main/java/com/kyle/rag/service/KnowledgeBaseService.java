package com.kyle.rag.service;

import com.kyle.rag.dto.KnowledgeBaseCreateRequest;
import com.kyle.rag.dto.KnowledgeBaseVO;
import com.kyle.rag.entity.*;
import com.kyle.rag.repository.*;
import com.kyle.rag.security.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;

/**
 * 知识库管理服务:串联知识库/授权、文档记录(PG)、原件(MinIO)、索引任务(IndexService)四个环节。
 * 删除策略是"文档记录软删 + 分块硬删":记录留作审计/恢复,分块量大且可由原件重建、无保留价值。
 * 权限判定:ADMIN 角色全量可见;其他用户取"用户授权 ∪ 部门授权"的最高级别,公开库兜底 READ。
 *
 * @author kyle
 * @version 1.0
 * @date 2026/11/2 16:43
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class KnowledgeBaseService {

    private final KnowledgeBaseRepository kbRepository;
    private final KbPermissionRepository permissionRepository;
    private final KbDocumentRepository documentRepository;
    private final DocChunkRepository chunkRepository;
    private final IndexTaskRepository taskRepository;
    private final MinioStorageService minioService;
    private final IndexService indexService;

    /** 权限级别排序,数值大者为高;higherPermission 据此合并用户/部门两路授权 */
    private static final Map<String, Integer> PERM_LEVEL = Map.of(
            "READ", 1, "WRITE", 2, "ADMIN", 3);

    /** 创建知识库,创建者自动获得该库的 ADMIN 授权(写入 kb_permission,主体类型 USER) */
    @Transactional
    public KnowledgeBase create(KnowledgeBaseCreateRequest req) {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setName(req.getName());
        kb.setDescription(req.getDescription());
        kb.setDepartmentId(req.getDepartmentId());
        kb.setIsPublic(req.getIsPublic());
        kb.setCreatedBy(UserContext.getUserId());

        KnowledgeBase saved = kbRepository.save(kb);

        // 创建者自动获得 ADMIN 权限
        KbPermission perm = new KbPermission();
        perm.setKbId(saved.getId());
        perm.setSubjectType("USER");
        perm.setSubjectId(String.valueOf(UserContext.getUserId()));
        perm.setPermission("ADMIN");
        perm.setGrantedBy(UserContext.getUserId());
        permissionRepository.save(perm);

        log.info("[KB] 知识库创建：id={}，name={}，creator={}", saved.getId(), saved.getName(), UserContext.getUserId());
        return saved;
    }

    /**
     * 查询当前用户可访问的知识库列表，并附带权限级别。
     */
    public List<KnowledgeBaseVO> listAccessible() {
        String dept = UserContext.getDepartmentId();
        String role = UserContext.getRole();
        String userId = String.valueOf(UserContext.getUserId());

        List<KnowledgeBase> kbList;
        if ("ADMIN".equalsIgnoreCase(role)) {
            kbList = kbRepository.findAll();   // @SoftDelete 自动过滤已删行
            return kbList.stream().map(kb -> toVO(kb, "ADMIN")).toList();
        }

        // 收集用户/部门的权限映射：kbId -> 最高权限
        Map<Long, String> permMap = new HashMap<>();

        permissionRepository.findBySubjectTypeAndSubjectId("DEPARTMENT", dept)
                .forEach(p -> permMap.merge(p.getKbId(), p.getPermission(), this::higherPermission));

        permissionRepository.findBySubjectTypeAndSubjectId("USER", userId)
                .forEach(p -> permMap.merge(p.getKbId(), p.getPermission(), this::higherPermission));

        // 公开库：没有显式权限的给 READ
        Set<Long> accessibleIds = new HashSet<>(permMap.keySet());
        kbRepository.findByIsPublicTrue().forEach(kb -> {
            accessibleIds.add(kb.getId());
            permMap.putIfAbsent(kb.getId(), "READ");
        });

        if (accessibleIds.isEmpty()) return List.of();

        return kbRepository.findAllById(accessibleIds).stream()
                .map(kb -> toVO(kb, permMap.getOrDefault(kb.getId(), "READ")))
                .toList();
    }

    /** 实体 → 列表 VO,附带当前用户对该库的权限级别 */
    private KnowledgeBaseVO toVO(KnowledgeBase kb, String permission) {
        return KnowledgeBaseVO.builder()
                .id(kb.getId())
                .name(kb.getName())
                .description(kb.getDescription())
                .departmentId(kb.getDepartmentId())
                .isPublic(kb.getIsPublic())
                .createdBy(kb.getCreatedBy())
                .createdAt(kb.getCreatedAt())
                .permission(permission)
                .build();
    }

    /** 两个权限取更高的一级;未知字符串按 0 处理,合并时会被已知级别覆盖 */
    private String higherPermission(String a, String b) {
        return PERM_LEVEL.getOrDefault(a, 0) >= PERM_LEVEL.getOrDefault(b, 0) ? a : b;
    }

    /**
     * 上传文档：存 MinIO → 创建文档记录 → 提交索引任务。
     * 返回文档 ID，前端可轮询状态。
     */
    @Transactional
    public KbDocument uploadDocument(Long kbId, MultipartFile file) {
        // 校验文件类型
        String fileName = file.getOriginalFilename();
        validateFileType(fileName);

        // 上传到 MinIO
        String minioPath = minioService.upload(kbId, file);

        // 创建文档记录
        KbDocument doc = new KbDocument();
        doc.setKbId(kbId);
        doc.setFileName(fileName);
        doc.setFileType(detectFileType(fileName));
        doc.setFileSize(file.getSize());
        doc.setMinioPath(minioPath);
        doc.setUploadedBy(UserContext.getUserId());
        KbDocument saved = documentRepository.save(doc);

        // 异步提交索引任务
        indexService.submitIndexTask(saved.getId());

        log.info("[KB] 文档上传：docId={}，fileName={}，kbId={}", saved.getId(), fileName, kbId);
        return saved;
    }

    /**
     * 删除文档：软删除文档记录 + 硬删除向量数据 + 删除 MinIO 文件。
     */
    @Transactional
    public void deleteDocument(Long docId) {
        KbDocument doc = documentRepository.findById(docId)
                .orElseThrow(() -> new RuntimeException("文档不存在：" + docId));

        // 软删除文档记录:@SoftDelete 下 delete 会被 Hibernate 翻译成 UPDATE is_deleted=true
        documentRepository.delete(doc);

        // 硬删除向量数据（向量数据不做软删除，占空间且不需要恢复）
        chunkRepository.deleteByDocId(docId);

        // 删除 MinIO 原件:尽力而为,失败只记日志不抛,不影响主流程
        minioService.delete(doc.getMinioPath());

        log.info("[KB] 文档删除：docId={}，fileName={}", docId, doc.getFileName());
    }

    /**
     * 重建索引：递增版本号 → 提交新索引任务 → 新任务完成后旧分块自动清理。
     */
    @Transactional
    public void reindex(Long docId) {
        KbDocument doc = documentRepository.findById(docId)
                .orElseThrow(() -> new RuntimeException("文档不存在：" + docId));

        // 版本号递增，索引完成后旧版本分块会被清理
        doc.setVersion(doc.getVersion() + 1);
        doc.setStatus(KbDocument.DocumentStatus.PENDING);
        doc.setErrorMsg(null);
        documentRepository.save(doc);

        indexService.submitIndexTask(docId);
        log.info("[KB] 触发重建索引：docId={}，newVersion={}", docId, doc.getVersion());
    }

    /** 按扩展名白名单校验;支持范围须与解析器注册表(DocumentLoaderService)人工保持一致 */
    private void validateFileType(String fileName) {
        if (fileName == null) throw new RuntimeException("文件名不能为空");
        String lower = fileName.toLowerCase();
        if (!lower.endsWith(".pdf") && !lower.endsWith(".docx") &&
                !lower.endsWith(".md")  && !lower.endsWith(".txt")) {
            throw new RuntimeException("不支持的文件类型，目前支持：PDF、DOCX、MD、TXT");
        }
    }

    /** 扩展名 → kb_document.file_type 取值(PDF / DOCX / MD / TXT) */
    private String detectFileType(String fileName) {
        if (fileName == null) return "UNKNOWN";
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".pdf"))  return "PDF";
        if (lower.endsWith(".docx")) return "DOCX";
        if (lower.endsWith(".md"))   return "MD";
        if (lower.endsWith(".txt"))  return "TXT";
        return "UNKNOWN";
    }
}
