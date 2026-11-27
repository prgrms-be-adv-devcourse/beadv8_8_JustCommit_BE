package com.justcommit.backend.market.order.application;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

// 정산 대상 조회 커서 (#657): 앞 페이지 마지막 행의 (confirmedAt, sellerOrderId).
// 조회는 (confirmedAt, sellerOrderId) 오름차순이고, 다음 페이지는 이 값보다 뒤인 행부터.
// 문자열은 "{confirmedAt ISO}_{sellerOrderId}" (예: 2026-11-25T10:30:00.123456_988593).
// confirmedAt은 DB 값(마이크로초) 그대로 담아야 같은 시각의 행이 페이지 경계에서 빠지거나 겹치지 않는다
public record SettlementTargetCursor(LocalDateTime confirmedAt, long sellerOrderId) {

    private static final char SEPARATOR = '_';

    // 첫 페이지: (from, 0)보다 뒤 = confirmedAt이 from 이상 (sellerOrderId는 1부터)
    static SettlementTargetCursor first(LocalDateTime from) {
        return new SettlementTargetCursor(from, 0L);
    }

    public String encode() {
        return DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(confirmedAt) + SEPARATOR + sellerOrderId;
    }

    // 형식이 틀리거나 sellerOrderId가 1보다 작으면 IllegalArgumentException
    public static SettlementTargetCursor parse(String value) {
        int at = value.lastIndexOf(SEPARATOR);
        if (at <= 0 || at == value.length() - 1) {
            throw new IllegalArgumentException("cursor 형식이 아닙니다: " + value);
        }
        try {
            LocalDateTime confirmedAt = LocalDateTime.parse(value.substring(0, at));
            long sellerOrderId = Long.parseLong(value.substring(at + 1));
            if (sellerOrderId < 1) {
                throw new IllegalArgumentException("cursor의 sellerOrderId는 1 이상이어야 합니다: " + value);
            }
            return new SettlementTargetCursor(confirmedAt, sellerOrderId);
        } catch (DateTimeParseException | NumberFormatException e) {
            throw new IllegalArgumentException("cursor 형식이 아닙니다: " + value, e);
        }
    }
}
