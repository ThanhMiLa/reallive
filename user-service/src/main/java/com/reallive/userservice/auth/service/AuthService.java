package com.reallive.userservice.auth.service;

import com.reallive.userservice.auth.dto.request.AuthRequest;
import com.reallive.userservice.auth.dto.response.AuthResponse;

public interface AuthService {
    AuthResponse login(AuthRequest authRequest);
}
