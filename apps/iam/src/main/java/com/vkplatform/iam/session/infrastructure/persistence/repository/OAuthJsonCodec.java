package com.vkplatform.iam.session.infrastructure.persistence.repository;

import org.springframework.security.jackson.SecurityJacksonModules;
import org.springframework.security.oauth2.server.authorization.jackson.OAuth2AuthorizationServerJacksonModule;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

final class OAuthJsonCodec {
    private final ObjectMapper objectMapper;

    OAuthJsonCodec() {
        ClassLoader classLoader = OAuthJsonCodec.class.getClassLoader();
        this.objectMapper = JsonMapper.builder()
                .addModules(SecurityJacksonModules.getModules(classLoader))
                .addModule(new OAuth2AuthorizationServerJacksonModule())
                .build();
    }

    Map<String, Object> readMap(String value) {
        if (value == null || value.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(value, new TypeReference<>() { });
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("OAuth persistence JSON is invalid", exception);
        }
    }

    String writeMap(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(normalize(value));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("OAuth persistence JSON cannot be encoded", exception);
        }
    }

    private static Object normalize(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            map.forEach((key, item) -> normalized.put(key.toString(), normalize(item)));
            return normalized;
        }
        if (value instanceof Collection<?> collection) {
            return collection.stream().map(OAuthJsonCodec::normalize).collect(
                    java.util.stream.Collectors.toCollection(ArrayList::new)
            );
        }
        return value;
    }
}
