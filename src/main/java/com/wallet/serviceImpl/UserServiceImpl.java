package com.wallet.serviceImpl;

import com.wallet.dto.LoginRequest;
import com.wallet.dto.LoginResponse;
import com.wallet.dto.SignupRequest;
import com.wallet.dto.SignupResponse;
import com.wallet.entity.User;
import com.wallet.repository.UserRepository;
import com.wallet.security.JwtService;
import com.wallet.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import static net.logstash.logback.argument.StructuredArguments.kv;

@Service
public class UserServiceImpl implements UserService {

    private static final Logger log = LoggerFactory.getLogger(UserServiceImpl.class);

    private final UserRepository userRepository;
    private final UserTxHelper userTxHelper;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public UserServiceImpl(
            UserRepository userRepository,
            UserTxHelper userTxHelper,
            PasswordEncoder passwordEncoder,
            JwtService jwtService) {
        this.userRepository = userRepository;
        this.userTxHelper = userTxHelper;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @Override
    public SignupResponse signup(SignupRequest request) {

        User candidate = new User();
        candidate.setUsername(request.getUsername());
        candidate.setPhoneNumber(request.getPhoneNumber());
        candidate.setPasswordHash(passwordEncoder.encode(request.getPassword()));

        User created;
        try {
            created = userTxHelper.tryCreateUser(candidate);
        } catch (DataIntegrityViolationException e) {
            log.warn("signup_conflict",
                    kv("event", "signup_conflict"),
                    kv("username", request.getUsername()));

            throw new ResponseStatusException(HttpStatus.CONFLICT, "username or phone number already in use");
        }

        log.info("user_created",
                kv("event", "user_created"),
                kv("userId", created.getId()),
                kv("username", created.getUsername()));

        return new SignupResponse(created.getId(), created.getUsername(), "user created successfully");
    }

    @Override
    public LoginResponse login(LoginRequest request) {

        User user = userRepository.findByUsername(request.getUsername())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid username or password"));

        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid username or password");
        }

        return issueToken(user);
    }

    private LoginResponse issueToken(User user) {
        String token = jwtService.generateToken(user.getId());
        return new LoginResponse(user.getId(), token, "Bearer", jwtService.getExpirationMs());
    }
}
