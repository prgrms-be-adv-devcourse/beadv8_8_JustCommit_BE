package com.justcommit.backend.market.shipment.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

// 우편번호 구간 목록 <-> jsonb 칸의 JSON 문자열
@Converter
public class ZipcodeRangesConverter implements AttributeConverter<List<ZipcodeRange>, String> {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<List<ZipcodeRange>> TYPE = new TypeReference<>() {};

    @Override
    public String convertToDatabaseColumn(List<ZipcodeRange> ranges) {
        return JSON.writeValueAsString(ranges);
    }

    @Override
    public List<ZipcodeRange> convertToEntityAttribute(String json) {
        return JSON.readValue(json, TYPE);
    }
}
