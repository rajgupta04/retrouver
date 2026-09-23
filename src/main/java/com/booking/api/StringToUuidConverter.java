package com.booking.api;

import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

/**
 * Converts String path variables / request params to UUID.
 * Supports standard UUID strings as well as human-friendly aliases like "room-001".
 */
@Component
public class StringToUuidConverter implements Converter<String, UUID> {

    public static final UUID ROOM_1_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    public static final UUID ROOM_2_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static final Map<String, UUID> ALIASES = Map.of(
            "room-001", ROOM_1_ID,
            "room-1", ROOM_1_ID,
            "room-002", ROOM_2_ID,
            "room-2", ROOM_2_ID
    );

    @Override
    public UUID convert(String source) {
        if (source == null || source.isBlank()) {
            return null;
        }
        UUID aliased = ALIASES.get(source.toLowerCase());
        if (aliased != null) {
            return aliased;
        }
        try {
            return UUID.fromString(source);
        } catch (IllegalArgumentException e) {
            // Deterministic UUID fallback for arbitrary names
            return UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8));
        }
    }
}
