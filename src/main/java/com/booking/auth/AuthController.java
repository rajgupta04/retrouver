package com.booking.auth;

import com.booking.domain.User;
import com.booking.repository.UserRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * Auth endpoints — register and login. No session state on the server;
 * the JWT token IS the session, carried in the Authorization header.
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final UserRepository userRepo;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;

    public AuthController(UserRepository userRepo, JwtService jwtService,
                          PasswordEncoder passwordEncoder) {
        this.userRepo = userRepo;
        this.jwtService = jwtService;
        this.passwordEncoder = passwordEncoder;
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody RegisterRequest request) {
        // Check for duplicate email — app-level check first (nicer error),
        // DB UNIQUE constraint is the real guarantee.
        if (userRepo.findByEmail(request.email()).isPresent()) {
            return ResponseEntity.status(409)
                    .body(Map.of("error", "Email already registered"));
        }

        String hashedPassword = passwordEncoder.encode(request.password());
        User user = new User(UUID.randomUUID(), request.name(), request.email(),
                request.timezone(), hashedPassword);
        userRepo.save(user);

        String token = jwtService.generateToken(user.getId(), user.getEmail());
        return ResponseEntity.status(201)
                .body(Map.of("token", token, "userId", user.getId()));
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request) {
        User user = userRepo.findByEmail(request.email())
                .orElse(null);

        if (user == null || !passwordEncoder.matches(request.password(),
                user.getPasswordHash())) {
            // Same error for "user not found" and "wrong password" —
            // don't leak which emails are registered.
            return ResponseEntity.status(401)
                    .body(Map.of("error", "Invalid email or password"));
        }

        String token = jwtService.generateToken(user.getId(), user.getEmail());
        return ResponseEntity.ok(Map.of("token", token, "userId", user.getId()));
    }

    public record RegisterRequest(String name, String email,
                                   String timezone, String password) {}

    public record LoginRequest(String email, String password) {}
}
