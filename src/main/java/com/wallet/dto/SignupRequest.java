package com.wallet.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class SignupRequest {

    @NotBlank
    private String username;

    @NotBlank
    @Size(min = 6, message = "password must be at least 6 characters")
    private String password;

    @NotBlank
    @Pattern(regexp = "^[0-9]{10}$", message = "phoneNumber must be exactly 10 digits")
    private String phoneNumber;
}
