package com.wallet;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;

// UserDetailsServiceAutoConfiguration is excluded because auth is handled
// entirely by our own JwtAuthenticationFilter/UserService - without this
// exclusion Spring Security still auto-generates an unused in-memory user
// and logs a "generated security password" warning on every startup.
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class WalletServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(WalletServiceApplication.class, args);
    }
}
