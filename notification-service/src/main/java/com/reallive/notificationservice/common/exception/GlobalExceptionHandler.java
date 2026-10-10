package com.reallive.notificationservice.common.exception;


import com.reallive.notificationservice.common.dto.ApiResponse;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import java.time.Instant;

@ControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiResponse<Object>> dataAccessException(DataAccessException exception) {
        return appException(new AppException(ErrorCode.EMAIL_HISTORY_UNAVAILABLE));
    }

    @ExceptionHandler(AppException.class)
    public ResponseEntity<ApiResponse<Object>> appException(AppException exception){
        ErrorCode errorCode = exception.getErrorCode();
        return ResponseEntity.status(errorCode.getHttpStatus())
                .body(ApiResponse.builder()
                        .status(errorCode.getHttpStatus().value())
                        .code(errorCode.getCode())
                        .message(errorCode.getMessage())
                        .result(null)
                        .timestamp(Instant.now().toString())
                        .build());
    }
}
