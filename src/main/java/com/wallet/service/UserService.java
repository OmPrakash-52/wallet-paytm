package com.wallet.service;

import com.wallet.dto.LoginRequest;
import com.wallet.dto.LoginResponse;
import com.wallet.dto.SignupRequest;

public interface UserService {

    /**
     * Creates a new account. Throws (409) if the username is already taken.
     */
    LoginResponse signup(SignupRequest request);

    /**
     * Verifies username/password and issues a JWT. Throws (401) on any
     * mismatch - deliberately the same error for "no such user" and "wrong
     * password" to avoid leaking which usernames exist.
     */
    LoginResponse login(LoginRequest request);
}
