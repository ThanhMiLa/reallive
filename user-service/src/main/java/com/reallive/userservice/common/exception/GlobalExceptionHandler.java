package com.reallive.userservice.common.exception;


import com.reallive.userservice.common.dto.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import java.time.Instant;

@ControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Object>> uploadTooLarge(MaxUploadSizeExceededException exception) {
        return appException(new AppException(ErrorCode.AVATAR_TOO_LARGE));
    }

    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<ApiResponse<Object>> invalidMultipart(MultipartException exception) {
        return ResponseEntity.badRequest().body(ApiResponse.builder()
                .status(400)
                .code(4000)
                .message("Invalid multipart request")
                .build());
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

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Object>> inValidInput(MethodArgumentNotValidException exception){
        FieldError bindingError = exception.getBindingResult().getFieldErrors().stream()
                .filter(FieldError::isBindingFailure)
                .findFirst()
                .orElse(null);
        if (bindingError != null && "dob".equals(bindingError.getField())) {
            return appException(new AppException(ErrorCode.DOB_INVALID));
        }
        ObjectError error = bindingError != null ? bindingError : exception.getBindingResult().getAllErrors().getFirst();
        try {
            return appException(new AppException(ErrorCode.valueOf(error.getDefaultMessage())));
        } catch (IllegalArgumentException | NullPointerException invalidErrorCode) {
            return ResponseEntity.badRequest().body(ApiResponse.builder()
                    .status(400)
                    .code(4000)
                    .message("Invalid request input")
                    .build());
        }
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Object>> handleTypeMismatch(MethodArgumentTypeMismatchException exception ){
        String paramName = exception.getName();
        String wrongValue = exception.getValue() != null ? exception.getValue().toString() : "null";
        String expectedType = exception.getRequiredType() != null ? exception.getRequiredType().getSimpleName() : "unknown";

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.builder()
                        .status(400)
                        .code(4000)
                        .message(String.format("Type mismatch for parameter '%s': value '%s' cannot be converted to '%s'.",
                                paramName, wrongValue, expectedType))
                        .result(null)
                        .timestamp(Instant.now().toString())
                        .build());
    }

}
