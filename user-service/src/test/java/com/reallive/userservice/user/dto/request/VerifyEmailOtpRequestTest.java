package com.reallive.userservice.user.dto.request;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.reallive.userservice.common.exception.ErrorCode;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerifyEmailOtpRequestTest {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        validatorFactory.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"012345", "000000", "123456", "999999"})
    void acceptsExactlySixAsciiDigits(String otp) {
        assertTrue(errors(new VerifyEmailOtpRequest(otp)).isEmpty());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            " ", "\t\n", "12345", "1234567", "12345a", "123 45", "-12345",
            " 123456", "123456 ", "123456\n", "１２３４５６", "١٢٣٤٥٦"
    })
    void rejectsMissingOrMalformedOtp(String otp) {
        assertEquals(Set.of(ErrorCode.OTP_INVALID.name()), errors(new VerifyEmailOtpRequest(otp)));
    }

    @Test
    void preservesLeadingZeroFromJson() throws Exception {
        VerifyEmailOtpRequest request = new ObjectMapper()
                .readValue("{\"otp\":\"012345\"}", VerifyEmailOtpRequest.class);

        assertEquals("012345", request.getOtp());
        assertTrue(errors(request).isEmpty());
    }

    @Test
    void excludesOtpFromToString() {
        VerifyEmailOtpRequest request = new VerifyEmailOtpRequest("012345");

        assertFalse(request.toString().contains("012345"));
    }

    private Set<String> errors(VerifyEmailOtpRequest request) {
        return validator.validate(request).stream()
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.toSet());
    }
}
