package com.kyle.rag.config;

import io.minio.MinioClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
/**
 * MinIO 客户端配置：读取 yml 的 minio.*（真实值来自 .env）。
 * 客户端是懒连接的，启动时不连 MinIO，不影响应用启动。
 *
 * @author kyle
 * @version 1.0
 * @date 2026/10/19 20:00
 */
@Configuration
@Slf4j
public class MinioConfig {

    /** MinIO 服务地址，如 http://host:9100 */
    @Value("${minio.endpoint}")
    private String endpoint;

    /** 访问账号（access key） */
    @Value("${minio.access-key}")
    private String accessKey;

    /** 访问密钥（secret key） */
    @Value("${minio.secret-key}")
    private String secretKey;

    /** MinioClient 线程安全，全项目共用这一个实例 */
    @Bean
    public MinioClient minioClient() {
        log.info("初始化 MinIO 客户端，endpoint={}", endpoint);
        return MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
    }
}
