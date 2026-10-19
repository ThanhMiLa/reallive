package com.reallive.channelservice.channel.dto.request;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnfollowedChannelListRequestValidationTest {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void createValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        validatorFactory.close();
    }

    @Test
    void categoryIdsMayBeNullButMustContainAtLeastOneElementWhenSupplied() {
        UnfollowedChannelListRequest request = new UnfollowedChannelListRequest();
        assertTrue(validator.validate(request).isEmpty());

        request.setCategoryIds(Set.of());
        assertTrue(validator.validate(request).stream()
                .anyMatch(violation -> violation.getPropertyPath().toString().equals("categoryIds")));

        request.setCategoryIds(Set.of(UUID.randomUUID()));
        assertTrue(validator.validate(request).isEmpty());
    }

    @Test
    void categoryIdsRejectNullElements() {
        UnfollowedChannelListRequest request = UnfollowedChannelListRequest.builder().categoryIds(Collections.singleton(null)).build();
        assertTrue(validator.validate(request).stream()
                .anyMatch(violation -> violation.getPropertyPath().toString().startsWith("categoryIds")));
    }

    @Test
    void unfollowedRequestsValidateOffsetsAndDefaults() {
        assertTrue(validator.validate(new UnfollowedChannelListRequest()).isEmpty());
        assertTrue(validator.validate(UnfollowedChannelListRequest.builder().build()).isEmpty());
        assertFalse(validator.validate(UnfollowedChannelListRequest.builder().page(Integer.MAX_VALUE).build()).isEmpty());
    }

    @Test
    void keywordLengthConstraintAppliesToTheRawFieldIncludingWhitespace() {
        UnfollowedChannelListRequest request = new UnfollowedChannelListRequest();
        assertTrue(validator.validate(request).isEmpty());

        request.setKeyword("a".repeat(255));
        assertTrue(validator.validate(request).isEmpty());

        request.setKeyword("a".repeat(256));
        assertTrue(validator.validate(request).stream()
                .anyMatch(violation -> violation.getPropertyPath().toString().equals("keyword")));

        request.setKeyword(" " + "a".repeat(255) + " ");
        assertTrue(validator.validate(request).stream()
                .anyMatch(violation -> violation.getPropertyPath().toString().equals("keyword")));
    }
}
