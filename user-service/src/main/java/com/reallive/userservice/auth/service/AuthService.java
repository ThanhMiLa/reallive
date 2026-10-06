package com.reallive.userservice.auth.service;

import com.reallive.userservice.auth.dto.request.AuthRequest;
import com.reallive.userservice.auth.dto.request.RegisterRequest;
import com.reallive.userservice.auth.dto.response.AuthResponse;

public interface AuthService {
    AuthResponse login(AuthRequest authRequest);
    AuthResponse register(RegisterRequest registerRequest);
}
