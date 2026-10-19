package com.kyle.rag.config;

import org.springframework.boot.task.ThreadPoolTaskExecutorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 双线程池：taskExecutor 业务通用（@Async 默认，参数走 yml），
 * indexTaskExecutor 文档索引专用（需 @Async("indexTaskExecutor") 指定）。
 * 自定义 Executor 会让 Boot 的自动装配退位，业务池须在此用 Builder 显式声明。
 *
 * @author kyle
 * @version 1.0
 * @date 2026/10/19 16:55
 */
@Configuration
public class AsyncConfig {

    /** 业务通用池：参数来自 yml 的 spring.task.execution */
    @Bean("taskExecutor")
    public ThreadPoolTaskExecutor taskExecutor(ThreadPoolTaskExecutorBuilder builder) {
        ThreadPoolTaskExecutor executor = builder.build();
        // 关闭时等任务跑完再停
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        return executor;
    }

    /** 文档索引专用池，与业务池隔离；IO 密集（解析 + Embedding 调用） */
    @Bean("indexTaskExecutor")
    public ThreadPoolTaskExecutor indexTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("index-");
        // 队列满时由提交线程自己执行，不丢任务
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // 索引任务耗时长，关闭时多等一会
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        return executor;
    }
}
