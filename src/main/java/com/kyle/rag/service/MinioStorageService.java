package com.kyle.rag.service;

import io.minio.*;
import io.minio.errors.MinioException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.UUID;

/**
 * 原始文档的对象存储访问层(MinIO,S3 兼容)。
 * 只存上传的原件,元数据在 kb_document、分块向量在 kb_doc_chunk;kb_document.minio_path 即本类返回的 objectPath。
 * 原件常驻不删(除非文档被删除),重建索引时从这里重新下载解析。
 * 所有操作共用 yml 配置的单个桶(minio.bucket),对象 key 按 kb/{kbId}/ 前缀分目录。
 *
 * @author kyle
 * @version 1.0
 * @date 2026/10/27 11:22
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MinioStorageService {

    /** MinioConfig 提供的单例客户端,线程安全 */
    private final MinioClient minioClient;

    /** 存文档的桶名(yml minio.bucket),不存在时由 ensureBucketExists 自动创建 */
    @Value("${minio.bucket}")
    private String bucket;

    /**
     * 上传文件到 MinIO,返回对象路径(调用方写入 kb_document.minio_path)。
     * 路径格式:kb/{kbId}/{uuid 前 8 位}-{originalFileName},uuid 前缀避免同名覆盖。
     * 失败统一包成 RuntimeException 抛出,由上层决定是否回滚文档记录。
     */
    public String upload(Long kbId, MultipartFile file) {
        String objectPath = String.format("kb/%d/%s-%s",
                kbId, UUID.randomUUID().toString().substring(0, 8), file.getOriginalFilename());
        try {
            ensureBucketExists();
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectPath)
                    .stream(file.getInputStream(), file.getSize(), -1)
                    .contentType(file.getContentType())
                    .build());
            log.info("[MinIO] 上传成功：path={}", objectPath);
            return objectPath;
        } catch (Exception e) {
            log.error("[MinIO] 上传失败：path={}，error={}", objectPath, e.getMessage(), e);
            throw new RuntimeException("文件上传失败：" + e.getMessage(), e);
        }
    }

    /**
     * 从 MinIO 下载文件内容,整个对象读入内存(与解析器的 readAllBytes 口径一致,受上传 50MB 上限约束)。
     * 失败抛 RuntimeException,IndexService.executeFromMinio 把它归为"可重试"阶段。
     */
    public byte[] download(String objectPath) {
        try {
            InputStream stream = minioClient.getObject(GetObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectPath)
                    .build());
            return stream.readAllBytes();
        } catch (Exception e) {
            log.error("[MinIO] 下载失败：path={}，error={}", objectPath, e.getMessage(), e);
            throw new RuntimeException("文件下载失败：" + e.getMessage(), e);
        }
    }

    /**
     * 删除 MinIO 中的文件(文档删除时调用)。
     * 删除是尽力而为:失败只记 warn 不抛,避免 MinIO 侧问题阻塞文档删除主流程。
     */
    public void delete(String objectPath) {
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectPath)
                    .build());
            log.info("[MinIO] 删除成功：path={}", objectPath);
        } catch (Exception e) {
            // 删除失败不抛出异常，只记录警告（文件可能已经不存在）
            log.warn("[MinIO] 删除失败（可能已不存在）：path={}，error={}", objectPath, e.getMessage());
        }
    }

    /** 桶不存在时自动创建,幂等;每次上传前调用,保证首次部署无需手工建桶 */
    private void ensureBucketExists() throws Exception {
        boolean exists = minioClient.bucketExists(BucketExistsArgs.builder()
                .bucket(bucket).build());
        if (!exists) {
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            log.info("[MinIO] Bucket 已创建：{}", bucket);
        }
    }
}
