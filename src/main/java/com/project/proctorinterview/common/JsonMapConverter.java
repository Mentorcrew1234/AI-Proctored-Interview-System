package com.project.proctorinterview.common;

import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stores a {@code Map<String, Object>} as a JSON object in a plain TEXT column.
 * Used for open-ended payloads: proctor event details (bounding box, head
 * direction, face count) and the report's proctoring summary counts.
 */
@Converter
public class JsonMapConverter implements AttributeConverter<Map<String, Object>, String> {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private static final TypeReference<Map<String, Object>> TYPE = new TypeReference<>() {
    };

    @Override
    public String convertToDatabaseColumn(Map<String, Object> attribute) {
        if (attribute == null || attribute.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(attribute);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialise map to JSON", e);
        }
    }

    @Override
    public Map<String, Object> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return MAPPER.readValue(dbData, TYPE);
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }
}
