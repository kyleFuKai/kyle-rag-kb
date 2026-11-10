package com.kyle.rag.service;

import com.kyle.rag.dto.KnowledgeBaseCreateRequest;
import com.kyle.rag.dto.KnowledgeBaseVO;
import com.kyle.rag.entity.*;
import com.kyle.rag.repository.*;
import com.kyle.rag.security.UserContext;
import com.kyle.rag.exception.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import com.kyle.rag.common.AuthConstants;
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
            AuthConstants.PERM_READ, 1, AuthConstants.PERM_WRITE, 2, AuthConstants.PERM_ADMIN, 3);

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
        perm.setSubjectType(AuthConstants.SUBJECT_USER);
        perm.setSubjectId(String.valueOf(UserContext.getUserId()));
        perm.setPermission(AuthConstants.PERM_ADMIN);
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
        if (AuthConstants.PERM_ADMIN.equalsIgnoreCase(role)) {
            kbList = kbRepository.findAll();   // @SoftDelete 自动过滤已删行
            return kbList.stream().map(kb -> toVO(kb, AuthConstants.PERM_ADMIN)).toList();
        }

        // 收集用户/部门的权限映射：kbId -> 最高权限
        Map<Long, String> permMap = new HashMap<>();

        permissionRepository.findBySubjectTypeAndSubjectId(AuthConstants.SUBJECT_DEPARTMENT, dept)
                .forEach(p -> permMap.merge(p.getKbId(), p.getPermission(), this::higherPermission));

        permissionRepository.findBySubjectTypeAndSubjectId(AuthConstants.SUBJECT_USER, userId)
                .forEach(p -> permMap.merge(p.getKbId(), p.getPermission(), this::higherPermission));

        // 公开库：没有显式权限的给 READ
        Set<Long> accessibleIds = new HashSet<>(permMap.keySet());
        kbRepository.findByIsPublicTrue().forEach(kb -> {
            accessibleIds.add(kb.getId());
            permMap.putIfAbsent(kb.getId(), AuthConstants.PERM_READ);
        });

        if (accessibleIds.isEmpty()) return List.of();

        return kbRepository.findAllById(accessibleIds).stream()
                .map(kb -> toVO(kb, permMap.getOrDefault(kb.getId(), AuthConstants.PERM_READ)))
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
        validateFileContent(file, fileName);

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
                .orElseThrow(() -> BizException.notFound("文档不存在：" + docId));

        // 软删除文档记录:条件 UPDATE 直接置 is_deleted=true(WHERE is_deleted=false),
        // 不加载实体再 delete——消除"查了再删"的并发竞态,天然幂等:
        // 并发删除时后到的 UPDATE 影响 0 行,不抛异常。返回 0 = 已被并发请求删除。
        documentRepository.softDeleteById(docId);

        // 硬删除向量数据（向量数据不做软删除，占空间且不需要恢复）
        chunkRepository.deleteByDocId(docId);

        // 删除 MinIO 原件:尽力而为,失败只记日志不抛,不影响主流程
        minioService.delete(doc.getMinioPath());

        log.info("[KB] 文档删除：docId={}，fileName={}", docId, doc.getFileName());
    }


    /** 按扩展名白名单校验;支持范围须与解析器注册表(DocumentLoaderService)人工保持一致 */
    private void validateFileType(String fileName) {
        if (fileName == null) throw BizException.badRequest("文件名不能为空");
        String lower = fileName.toLowerCase();
        if (!lower.endsWith(".pdf") && !lower.endsWith(".docx") &&
                !lower.endsWith(".md")  && !lower.endsWith(".txt")) {
            throw BizException.badRequest("不支持的文件类型，目前支持：PDF、DOCX、MD、TXT");
        }
    }

    /** 扩展名 → kb_document.file_type 取值(PDF / DOCX / MD / TXT) */
    /**
     * 校验文件内容与声明的类型一致:PDF/DOCX 查魔数,TXT/MD 查二进制特征。
     * 只看扩展名不够——把任意文件改名成 .pdf 就能绕过,恶意内容会被解析器当文本入库。
     */
    private void validateFileContent(MultipartFile file, String fileName) {
        byte[] head = new byte[1024];
        int read;
        try (InputStream in = file.getInputStream()) {
            read = in.read(head);
        } catch (IOException e) {
            throw BizException.badRequest("文件读取失败：" + e.getMessage());
        }
        if (read <= 0) {
            throw BizException.badRequest("文件内容为空");
        }

        String type = detectFileType(fileName);
        switch (type) {
            case "PDF" -> {
                // PDF 规范允许文件头有前导字节,魔数在前 1KB 内扫描
                if (!contains(head, read, "%PDF")) {
                    throw BizException.badRequest("文件内容不是有效的 PDF");
                }
            }
            case "DOCX" -> {
                // docx 是 ZIP 容器,魔数 PK
                if (!contains(head, read, "PK")) {
                    throw BizException.badRequest("文件内容不是有效的 DOCX");
                }
            }
            default -> {
                // TXT/MD 无魔数,靠二进制特征判断:出现 NUL 等控制字符即非文本
                if (looksBinary(head, read)) {
                    throw BizException.badRequest("文件内容不是有效的文本");
                }
            }
        }
    }

    /** 在已读字节的前 read 长度内查找 ASCII 标记 */
    private boolean contains(byte[] buf, int len, String marker) {
        byte[] m = marker.getBytes(StandardCharsets.US_ASCII);
        outer:
        for (int i = 0; i + m.length <= len; i++) {
            for (int j = 0; j < m.length; j++) {
                if (buf[i + j] != m[j]) continue outer;
            }
            return true;
        }
        return false;
    }

    /** 文本文件不应含 NUL 等控制字符(制表/换行/回车/换页除外) */
    private boolean looksBinary(byte[] buf, int len) {
        for (int i = 0; i < len; i++) {
            int c = buf[i] & 0xFF;
            if (c == 0) return true;
            if (c < 0x09 || (c > 0x0D && c < 0x20)) return true;
        }
        return false;
    }

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
