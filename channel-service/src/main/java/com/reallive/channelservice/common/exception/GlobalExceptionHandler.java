package com.reallive.channelservice.common.exception;

import com.reallive.channelservice.common.dto.ApiResponse;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.time.Instant;

@ControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<ApiResponse<Object>> invalidRequest(Exception exception) {
        return appException(new AppException(ErrorCode.CHANNEL_REQUEST_INVALID));
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiResponse<Object>> dataAccessException(DataAccessException exception) {
        return appException(new AppException(ErrorCode.CHANNEL_DATA_UNAVAILABLE));
    }

    @ExceptionHandler(AppException.class)
    public ResponseEntity<ApiResponse<Object>> appException(AppException exception) {
        ErrorCode errorCode = exception.getErrorCode();
        return ResponseEntity.status(errorCode.getHttpStatus())
                .body(ApiResponse.builder()
                        .status(errorCode.getHttpStatus().value())
                        .code(errorCode.getCode())
                        .message(errorCode.getMessage())
                        .result(errorCode == ErrorCode.CHANNEL_REQUEST_INVALID
                                || errorCode == ErrorCode.CHANNEL_DATA_UNAVAILABLE
                                || errorCode == ErrorCode.CHANNEL_ALREADY_EXISTED ? false : null)
                        .timestamp(Instant.now().toString())
                        .build());
    }
}
