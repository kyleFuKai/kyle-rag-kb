package com.kyle.rag.service;

import com.kyle.rag.security.UserContext;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
/**
 * 索引任务的异步投递出口。独立成 Bean 的两个原因:
 * ① @Async 只在经过 Spring 代理调用时生效,IndexService 内部的重投递(scheduleRetry)属于自调用、会绕过自身代理,
 *    所以 @Async 方法必须放在另一个 Bean 上;② 与 IndexService 互相依赖,用 @Lazy 打破构造期循环。
 * 职责:接收请求线程捕获的用户上下文快照,在索引池线程 set 后执行、finally 必清——
 * 池线程复用,不清会把上一个任务的身份串到下一个任务(ThreadLocal 跨线程传递的标准姿势)。
 *
 * @author kyle
 * @version 1.0
 * @date 2026/11/1 23:11
 */
@Component
public class IndexTaskLauncher {

    private final IndexService indexService;

    public IndexTaskLauncher(@Lazy IndexService indexService) {
        this.indexService = indexService;
    }

    /** MinIO 任务入口,也是 scheduleRetry 延迟重投递的唯一通道 */
    @Async("indexTaskExecutor")
    public void launchFromMinio(Long taskId, Long docId,
                                Long userId, String departmentId, String role) {
        UserContext.set(userId, departmentId, role);
        try {
            indexService.executeFromMinio(taskId, docId);
        } finally {
            UserContext.clear();
        }
    }

    /** 文本任务入口(测试用):文本只在内存里,失败后不参与自动重试 */
    @Async("indexTaskExecutor")
    public void launchWithText(Long taskId, Long docId, String textContent,
                               Long userId, String departmentId, String role) {
        UserContext.set(userId, departmentId, role);
        try {
            indexService.executeWithText(taskId, docId, textContent);
        } finally {
            UserContext.clear();
        }
    }
}
