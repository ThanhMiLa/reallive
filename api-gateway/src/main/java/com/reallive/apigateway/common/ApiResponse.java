package com.reallive.apigateway.common;

public record ApiResponse<T>(int status, int code, String message, T result, String timestamp) {
}
