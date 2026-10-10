package com.reallive.notificationservice.email.util;

import com.reallive.notificationservice.common.exception.AppException;
import com.reallive.notificationservice.common.exception.ErrorCode;
import com.reallive.notificationservice.email.EmailDeliveryTestSupport;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;

class EmailVerificationMessageReaderTest {

    private static final ValidatorFactory VALIDATORS = Validation.buildDefaultValidatorFactory();
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final EmailVerificationMessageReader reader = new EmailVerificationMessageReader(mapper, VALIDATORS.getValidator());

    @AfterAll
    static void closeValidators() {
        VALIDATORS.close();
    }

    @Test
    void preservesTheSharedContractAndLeadingZeroOtp() {
        var message = EmailDeliveryTestSupport.message(Instant.now());
        assertEquals(message, reader.read(message.getUserId().toString(), mapper.writeValueAsString(message)));
    }

    @Test
    void acceptsPasswordResetEmailType() {
        var message = EmailDeliveryTestSupport.message(Instant.now());
        message.setEmailType(com.reallive.notificationservice.email.dto.message.EmailType.PASSWORD_RESET);
        assertEquals(message, reader.read(message.getUserId().toString(), mapper.writeValueAsString(message)));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"null", "[]", "{", "{\"otp\":\"012345\"}", "private-012345"})
    void malformedMessagesRaiseASanitizedApplicationError(String json) {
        var error = assertThrows(AppException.class, () -> reader.read("user", json));
        assertEquals(ErrorCode.EMAIL_MESSAGE_INVALID, error.getErrorCode());
        assertFalse(error.getMessage().contains("012345"));
        assertNull(error.getCause());
    }

    @ParameterizedTest
    @ValueSource(strings = {"eventId", "challengeId", "userId", "emailType", "recipientEmail", "otp", "issuedAt", "expiresAt", "schemaVersion"})
    void missingRequiredFieldsAreRejected(String field) {
        var message = EmailDeliveryTestSupport.message(Instant.now());
        ObjectNode json = mapper.valueToTree(message);
        json.remove(field);
        assertThrows(AppException.class, () -> reader.read(message.getUserId().toString(), json.toString()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"12345", "1234567", "１２３４５６", "12345a", " 123456"})
    void onlySixAsciiDigitsAreAccepted(String otp) {
        var message = EmailDeliveryTestSupport.message(Instant.now());
        message.setOtp(otp);
        assertThrows(AppException.class, () -> reader.read(message.getUserId().toString(), mapper.writeValueAsString(message)));
    }

    @Test
    void numericOtpAndUnknownSchemaOrEmailTypeAreRejected() {
        var message = EmailDeliveryTestSupport.message(Instant.now());
        ObjectNode json = mapper.valueToTree(message);
        json.put("otp", 123456);
        assertThrows(AppException.class, () -> reader.read(message.getUserId().toString(), json.toString()));
        message.setSchemaVersion(2);
        assertThrows(AppException.class, () -> reader.read(message.getUserId().toString(), mapper.writeValueAsString(message)));
        json.put("otp", "012345").put("emailType", "FORGOT_PASSWORD");
        assertThrows(AppException.class, () -> reader.read(message.getUserId().toString(), json.toString()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "invalid", "recipient\n@example.com"})
    void invalidEmailsAreRejected(String email) {
        var message = EmailDeliveryTestSupport.message(Instant.now());
        message.setRecipientEmail(email);
        assertThrows(AppException.class, () -> reader.read(message.getUserId().toString(), mapper.writeValueAsString(message)));
    }

    @Test
    void keyMismatchAndInvalidTimeRangeAreRejected() {
        var message = EmailDeliveryTestSupport.message(Instant.now());
        assertThrows(AppException.class, () -> reader.read("different-user", mapper.writeValueAsString(message)));
        message.setExpiresAt(message.getIssuedAt());
        assertThrows(AppException.class, () -> reader.read(message.getUserId().toString(), mapper.writeValueAsString(message)));
    }
}
