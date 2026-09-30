package com.project.proctorinterview.common;

import java.util.List;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stores a {@code List<String>} as a JSON array in a plain TEXT column.
 *
 * <p>Deliberately not MySQL's native JSON type: keeping it TEXT means the same
 * entity mappings also work against H2 in the test suite.
 *
 * <p>Hibernate instantiates converters itself, so the mapper is a static field
 * rather than an injected bean.
 */
@Converter
public class StringListConverter implements AttributeConverter<List<String>, String> {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private static final TypeReference<List<String>> TYPE = new TypeReference<>() {
    };

    @Override
    public String convertToDatabaseColumn(List<String> attribute) {
        if (attribute == null || attribute.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(attribute);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialise list to JSON", e);
        }
    }

    @Override
    public List<String> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(dbData, TYPE);
        } catch (Exception e) {
            // A malformed row must not break the whole report page.
            return List.of();
        }
    }
}
