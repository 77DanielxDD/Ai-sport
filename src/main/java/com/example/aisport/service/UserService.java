package com.example.aisport.service;

import com.example.aisport.entity.User;
import com.example.aisport.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Set;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final Set<String> bootstrapAdminUsernames;

    public UserService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       @Value("${app.admin.usernames:admin,test_user_1}") String bootstrapAdmins) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.bootstrapAdminUsernames = Arrays.stream(bootstrapAdmins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }

    public User register(User user) {
        if (userRepository.findByUsername(user.getUsername()).isPresent()) {
            throw new RuntimeException("Username already exists");
        }
        user.setRole(bootstrapAdminUsernames.contains(user.getUsername()) ? User.UserRole.ADMIN : User.UserRole.USER);
        user.setEnabled(true);
        user.setPassword(passwordEncoder.encode(user.getPassword()));
        return userRepository.save(user);
    }

    public boolean login(String username, String password) {
        Optional<User> user = userRepository.findByUsername(username);
        if (user.isEmpty()) {
            return false;
        }

        String stored = user.get().getPassword();
        if (stored == null || password == null) {
            return false;
        }
        if (Boolean.FALSE.equals(user.get().getEnabled())) {
            return false;
        }

        try {
            if (passwordEncoder.matches(password, stored)) {
                return true;
            }
        } catch (IllegalArgumentException ignored) {
            // stored password may be legacy plain text; fallback below
        }

        // Backward compatibility: migrate legacy plain-text password to BCrypt on first successful login.
        if (stored.equals(password)) {
            user.get().setPassword(passwordEncoder.encode(password));
            userRepository.save(user.get());
            return true;
        }
        return false;
    }

    public Optional<User> findByUsername(String username) {
        return userRepository.findByUsername(username);
    }

    public User createUserByAdmin(String username,
                                  String rawPassword,
                                  String email,
                                  String roleText,
                                  Boolean enabled) {
        if (username == null || username.isBlank()) {
            throw new RuntimeException("Username is required");
        }
        if (rawPassword == null || rawPassword.isBlank()) {
            throw new RuntimeException("Password is required");
        }
        if (userRepository.findByUsername(username).isPresent()) {
            throw new RuntimeException("Username already exists");
        }

        User.UserRole role = User.UserRole.USER;
        if ("ADMIN".equalsIgnoreCase(roleText)) {
            role = User.UserRole.ADMIN;
        }

        User u = new User();
        u.setUsername(username.trim());
        u.setEmail(email);
        u.setRole(role);
        u.setEnabled(enabled == null || enabled);
        u.setPassword(passwordEncoder.encode(rawPassword));
        return userRepository.save(u);
    }

    public void deleteUser(User user) {
        userRepository.delete(user);
    }

    public User updateProfile(String currentUsername,
                              String newUsername,
                              String currentPassword,
                              String newPassword) {
        User user = userRepository.findByUsername(currentUsername)
                .orElseThrow(() -> new RuntimeException("User not found"));

        if (currentPassword == null || currentPassword.isBlank()) {
            throw new RuntimeException("Current password is required");
        }
        if (!login(currentUsername, currentPassword)) {
            throw new RuntimeException("Current password is incorrect");
        }

        if (newUsername != null && !newUsername.isBlank() && !newUsername.equals(currentUsername)) {
            if (userRepository.findByUsername(newUsername).isPresent()) {
                throw new RuntimeException("Username already exists");
            }
            user.setUsername(newUsername);
            if (bootstrapAdminUsernames.contains(newUsername)) {
                user.setRole(User.UserRole.ADMIN);
            }
        }

        if (newPassword != null && !newPassword.isBlank()) {
            user.setPassword(passwordEncoder.encode(newPassword));
        }
        return userRepository.save(user);
    }

    public boolean isAdmin(String username) {
        if (username == null) {
            return false;
        }
        if (bootstrapAdminUsernames.contains(username)) {
            return true;
        }
        return userRepository.findByUsername(username)
                .map(u -> u.getRole() == User.UserRole.ADMIN)
                .orElse(false);
    }
}
