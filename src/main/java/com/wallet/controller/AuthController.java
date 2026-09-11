package com.wallet.controller;

import com.wallet.dto.LoginRequest;
import com.wallet.dto.LoginResponse;
import com.wallet.dto.SignupRequest;
import com.wallet.service.UserService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final UserService userService;

    public AuthController(UserService userService) {
        this.userService = userService;
    }

    // POST /auth/signup
    // Creates a new account and returns a token immediately (so callers don't
    // need a separate login round-trip right after signing up).
    @PostMapping("/signup")
    public LoginResponse signup(@Valid @RequestBody SignupRequest request) {
        return userService.signup(request);
    }

    // POST /auth/login
    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        return userService.login(request);
    }
}
