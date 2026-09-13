package com.wallet.service;

import com.wallet.dto.LoginRequest;
import com.wallet.dto.LoginResponse;
import com.wallet.dto.SignupRequest;
import com.wallet.dto.SignupResponse;

public interface UserService {

    /**
     * Creates a new account. Throws (409) if the username or phone number is
     * already taken. Does NOT issue a token - signup only confirms the
     * account was created; call login separately to authenticate.
     */
    SignupResponse signup(SignupRequest request);

    /**
     * Verifies username/password and issues a JWT. Throws (401) on any
     * mismatch - deliberately the same error for "no such user" and "wrong
     * password" to avoid leaking which usernames exist.
     */
    LoginResponse login(LoginRequest request);
}
