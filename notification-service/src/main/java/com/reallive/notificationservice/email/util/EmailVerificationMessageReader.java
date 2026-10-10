package com.reallive.notificationservice.email.util;

import com.reallive.notificationservice.common.exception.AppException;
import com.reallive.notificationservice.common.exception.ErrorCode;
import com.reallive.notificationservice.email.dto.message.EmailType;
import com.reallive.notificationservice.email.dto.message.EmailVerificationRequestedMessage;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Email;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class EmailVerificationMessageReader {

    ObjectMapper objectMapper;
    Validator validator;

    public EmailVerificationRequestedMessage read(String key, String json) {
        try {
            var tree = objectMapper.readTree(json);
            if (tree == null || !tree.isObject() || !tree.path("schemaVersion").isIntegralNumber()
                    || !tree.path("otp").isTextual()) {
                throw new AppException(ErrorCode.EMAIL_MESSAGE_INVALID);
            }
            var message = objectMapper.treeToValue(tree, EmailVerificationRequestedMessage.class);
            validate(key, message);
            return message;
        } catch (RuntimeException exception) {
            throw new AppException(ErrorCode.EMAIL_MESSAGE_INVALID);
        }
    }

    private void validate(String key, EmailVerificationRequestedMessage message) {
        if (message == null || message.getSchemaVersion() != 1 || message.getEventId() == null
                || message.getChallengeId() == null || message.getUserId() == null
                || !message.getUserId().toString().equals(key) || message.getEmailType() != EmailType.EMAIL_VERIFICATION
                || message.getRecipientEmail() == null || message.getRecipientEmail().isBlank()
                || message.getRecipientEmail().length() > 255
                || !validator.validate(new Recipient(message.getRecipientEmail())).isEmpty()
                || message.getOtp() == null || !message.getOtp().matches("[0-9]{6}")
                || message.getIssuedAt() == null || message.getExpiresAt() == null
                || !message.getExpiresAt().isAfter(message.getIssuedAt())) {
            throw new AppException(ErrorCode.EMAIL_MESSAGE_INVALID);
        }
    }

    private record Recipient(@Email String email) {
    }
}
