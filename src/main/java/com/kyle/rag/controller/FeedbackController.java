package com.kyle.rag.controller;

import com.kyle.rag.dto.ApiResponse;
import com.kyle.rag.service.FeedbackService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
/**
 * 反馈的 REST 入口(/api/v1/feedback):对单条回答点赞/点踩。
 *
 * @author kyle
 * @version 1.0
 * @date 2026/11/10 00:44
 */
@RestController
@RequestMapping("/api/v1/feedback")
@RequiredArgsConstructor
public class FeedbackController {

    private final FeedbackService feedbackService;

    /** 提交反馈:feedback=1 赞 / -1 踩,comment 可选;重复提交覆盖 */
    @PostMapping("/{messageId}")
    public ApiResponse<Void> submitFeedback(
            @PathVariable Long messageId,
            @RequestParam int feedback,
            @RequestParam(required = false) String comment) {
        feedbackService.submitFeedback(messageId, feedback, comment);
        return ApiResponse.ok(null);
    }
}
