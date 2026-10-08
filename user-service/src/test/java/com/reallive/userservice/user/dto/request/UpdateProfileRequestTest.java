package com.reallive.userservice.user.dto.request;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.LocalDate;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateProfileRequestTest {

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

    @Test
    void allowsOmittedFieldsForPartialUpdates() {
        assertTrue(errors(new UpdateProfileRequest()).isEmpty());
    }

    @Test
    void allowsPartialUpdatesBuiltWithLombok() {
        UpdateProfileRequest request = UpdateProfileRequest.builder().displayName("x".repeat(100)).build();
        assertTrue(errors(request).isEmpty());
        assertEquals("x".repeat(100), request.getDisplayName());
        assertNull(request.getDob());
        assertNull(request.getAvatar());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\t\n", "\u2003"})
    void rejectsBlankNames(String value) {
        UpdateProfileRequest request = new UpdateProfileRequest();
        request.setDisplayName(value);
        assertEquals(Set.of("DISPLAY_NAME_INVALID"), errors(request));
    }

    @Test
    void rejectsNamesLongerThanTheDatabaseLimit() {
        UpdateProfileRequest request = new UpdateProfileRequest();
        request.setDisplayName("x".repeat(101));
        assertEquals(Set.of("DISPLAY_NAME_INVALID"), errors(request));
    }

    @Test
    void acceptsTodayAndPastDatesWithoutRequiringOtherFields() {
        UpdateProfileRequest request = new UpdateProfileRequest();
        request.setDob(LocalDate.now());
        assertTrue(errors(request).isEmpty());
        request.setDob(LocalDate.of(2000, 1, 1));
        assertTrue(errors(request).isEmpty());
    }

    @Test
    void rejectsFutureDatesThroughPastOrPresentConstraint() {
        UpdateProfileRequest request = new UpdateProfileRequest();
        request.setDob(LocalDate.now().plusDays(1));
        assertEquals(Set.of("DOB_INVALID"), errors(request));
    }

    @Test
    void allowsOmittedDobWithTheAllArgumentsConstructor() {
        UpdateProfileRequest request = new UpdateProfileRequest("Viewer", null, null);
        assertTrue(errors(request).isEmpty());
    }

    private Set<String> errors(UpdateProfileRequest request) {
        return validator.validate(request).stream().map(ConstraintViolation::getMessage).collect(Collectors.toSet());
    }
}
