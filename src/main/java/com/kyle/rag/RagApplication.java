package com.kyle.rag;

import me.paulschwarz.springdotenv.spring.DotenvApplicationInitializer;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * RAG 知识库服务启动入口。
 * 对话：MiniMax；向量化与重排：阿里百炼；存储：PostgreSQL/pgvector + Redis + MinIO。
 */
@SpringBootApplication
@EnableAsync   //开启 @Async：线程池见 AsyncConfig——业务默认池 taskExecutor（参数走 yml），索引池用 @Async("indexTaskExecutor") 指定
@EnableRetry   //开启 @Retryable 声明式重试：embedding 调用、索引任务等偶发失败自动重试 （依赖 pom 里的 spring-retry + spring-aspects）
public class RagApplication {

    public static void main(String[] args) {
        // 注册 DotenvApplicationInitializer：启动时自动读取项目根目录的 .env
        new SpringApplicationBuilder(RagApplication.class)
                .initializers(new DotenvApplicationInitializer())
                .run(args);
    }
}
