package com.wallet.serviceImpl;

import com.wallet.dto.LoginRequest;
import com.wallet.dto.LoginResponse;
import com.wallet.dto.SignupRequest;
import com.wallet.entity.User;
import com.wallet.repository.UserRepository;
import com.wallet.security.JwtService;
import com.wallet.service.UserService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class UserServiceImpl implements UserService {

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
    public LoginResponse signup(SignupRequest request) {

        User candidate = new User();
        candidate.setUsername(request.getUsername());
        candidate.setPhoneNumber(request.getPhoneNumber());
        candidate.setPasswordHash(passwordEncoder.encode(request.getPassword()));

        try {
            return issueToken(userTxHelper.tryCreateUser(candidate));
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "username or phone number already in use");
        }
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
