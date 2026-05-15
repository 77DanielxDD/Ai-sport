package com.example.aisport.controller;

import com.example.aisport.entity.User;
import com.example.aisport.security.JwtService;
import com.example.aisport.service.UserService;
import com.example.aisport.service.VideoService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.ArrayList;
import java.util.Map;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;
    private final JwtService jwtService;
    private final VideoService videoService;

    public UserController(UserService userService, JwtService jwtService, VideoService videoService) {
        this.userService = userService;
        this.jwtService = jwtService;
        this.videoService = videoService;
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody User user) {
        User savedUser = userService.register(user);
        String token = jwtService.generateToken(savedUser.getUsername());
        String role = userService.isAdmin(savedUser.getUsername()) ? "ADMIN" : (savedUser.getRole() == null ? "USER" : savedUser.getRole().name());
        return ResponseEntity.ok(Map.of(
                "userId", savedUser.getId(),
                "username", savedUser.getUsername(),
                "role", role,
                "token", token
        ));
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, Object> request) {
        String username = request.get("username") == null ? null : String.valueOf(request.get("username"));
        String password = request.get("password") == null ? null : String.valueOf(request.get("password"));

        boolean valid = userService.login(username, password);
        if (!valid) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Invalid credentials"));
        }

        User user = userService.findByUsername(username).orElseThrow();
        String role = userService.isAdmin(username) ? "ADMIN" : (user.getRole() == null ? "USER" : user.getRole().name());
        return ResponseEntity.ok(Map.of(
                "username", username,
                "role", role,
                "token", jwtService.generateToken(username)
        ));
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(Principal principal) {
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Unauthorized"));
        }
        return userService.findByUsername(principal.getName())
                .<ResponseEntity<?>>map(u -> ResponseEntity.ok(Map.of(
                        "userId", u.getId(),
                        "username", u.getUsername(),
                        "email", u.getEmail(),
                        "role", userService.isAdmin(u.getUsername()) ? "ADMIN" : (u.getRole() == null ? "USER" : u.getRole().name()),
                        "enabled", u.getEnabled() == null || u.getEnabled()
                )))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "User not found")));
    }

    @PutMapping("/me")
    public ResponseEntity<?> updateMe(@RequestBody Map<String, Object> request, Principal principal) {
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Unauthorized"));
        }
        String newUsername = request.get("username") == null ? null : String.valueOf(request.get("username")).trim();
        String currentPassword = request.get("currentPassword") == null ? null : String.valueOf(request.get("currentPassword"));
        String newPassword = request.get("newPassword") == null ? null : String.valueOf(request.get("newPassword"));
        try {
            User updated = userService.updateProfile(principal.getName(), newUsername, currentPassword, newPassword);
            String role = userService.isAdmin(updated.getUsername()) ? "ADMIN" : (updated.getRole() == null ? "USER" : updated.getRole().name());
            return ResponseEntity.ok(Map.of(
                    "userId", updated.getId(),
                    "username", updated.getUsername(),
                    "email", updated.getEmail() == null ? "" : updated.getEmail(),
                    "role", role,
                    "enabled", updated.getEnabled() == null || updated.getEnabled(),
                    "token", jwtService.generateToken(updated.getUsername())
            ));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/me/delete")
    public ResponseEntity<?> deleteMe(@RequestBody Map<String, Object> request, Principal principal) {
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Unauthorized"));
        }
        String currentPassword = request.get("currentPassword") == null ? null : String.valueOf(request.get("currentPassword"));
        if (currentPassword == null || currentPassword.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Current password is required"));
        }
        if (!userService.login(principal.getName(), currentPassword)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Current password is incorrect"));
        }

        User user = userService.findByUsername(principal.getName()).orElse(null);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "User not found"));
        }

        var videos = new ArrayList<>(videoService.findByUser(user));
        for (var v : videos) {
            videoService.deleteVideoCascade(v);
        }
        userService.deleteUser(user);
        return ResponseEntity.ok(Map.of("message", "Account deleted"));
    }

}
