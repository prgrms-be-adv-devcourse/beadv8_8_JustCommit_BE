package com.justcommit.backend.notification.presentation.dto;

import com.justcommit.backend.common.request.PagingRequest;
import org.springframework.data.domain.Sort;

// isRead가 없으면 전체, true·false면 읽은·안 읽은 알림만
public record NotificationListRequest(Boolean isRead, Integer page, Integer size) implements PagingRequest {
    @Override
    public Sort defaultSort() {
        return Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
    }
}
