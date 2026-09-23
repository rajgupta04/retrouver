package com.booking.repository;

import com.booking.domain.User;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class UserRepository {

    private final JdbcTemplate jdbc;

    public UserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private final RowMapper<User> rowMapper = (rs, rowNum) ->
            new User(
                    UUID.fromString(rs.getString("id")),
                    rs.getString("name"),
                    rs.getString("email"),
                    rs.getString("timezone"),
                    rs.getString("password_hash")
            );

    public void save(User user) {
        jdbc.update("""
            INSERT INTO users (id, name, email, timezone, password_hash, created_at)
            VALUES (?, ?, ?, ?, ?, ?)
        """,
                user.getId().toString(), user.getName(), user.getEmail(),
                user.getTimezone(), user.getPasswordHash(),
                Instant.now().toString());
    }

    public Optional<User> findById(UUID id) {
        List<User> results = jdbc.query("SELECT * FROM users WHERE id = ?",
                rowMapper, id.toString());
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public Optional<User> findByEmail(String email) {
        List<User> results = jdbc.query("SELECT * FROM users WHERE email = ?",
                rowMapper, email);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }
}
