package com.ake.aiops.web;

import jakarta.validation.constraints.NotBlank;

/** 对话请求体。 */
public record ChatRequest(
        @NotBlank(message = "message 不能为空") String message,
        String conversationId,
        String userId) {

    public String conversationIdOr(String fallback) {
        return conversationId == null || conversationId.isBlank() ? fallback : conversationId;
    }

    public String userIdOr(String fallback) {
        return userId == null || userId.isBlank() ? fallback : userId;
    }
}
