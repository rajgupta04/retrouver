package com.booking.repository;

import com.booking.domain.Room;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class RoomRepository {

    private final JdbcTemplate jdbc;

    public RoomRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private final RowMapper<Room> rowMapper = (rs, rowNum) -> {
        Room r = new Room(
                UUID.fromString(rs.getString("id")),
                rs.getString("name"),
                rs.getInt("capacity")
        );
        r.setActive(rs.getInt("active") == 1);
        return r;
    };

    @CacheEvict(value = "rooms", allEntries = true)
    public void save(Room room) {
        jdbc.update("INSERT INTO room (id, name, capacity, active) VALUES (?, ?, ?, ?)",
                room.getId().toString(), room.getName(), room.getCapacity(),
                room.isActive() ? 1 : 0);
    }

    @Cacheable(value = "rooms", key = "#id")
    public Optional<Room> findById(UUID id) {
        List<Room> results = jdbc.query("SELECT * FROM room WHERE id = ?",
                rowMapper, id.toString());
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    @Cacheable(value = "rooms", key = "'allActive'")
    public List<Room> findAllActive() {
        return jdbc.query("SELECT * FROM room WHERE active = 1", rowMapper);
    }

    @CacheEvict(value = "rooms", allEntries = true)
    public void deactivate(UUID id) {
        jdbc.update("UPDATE room SET active = 0 WHERE id = ?", id.toString());
    }
}
