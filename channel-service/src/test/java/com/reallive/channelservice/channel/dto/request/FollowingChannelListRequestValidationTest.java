package com.reallive.channelservice.channel.dto.request;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.data.domain.Sort;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FollowingChannelListRequestValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void createValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    @Test
    void constructorAndBuilderDefaultToFollowerCountDescending() {
        for (FollowingChannelListRequest request : new FollowingChannelListRequest[]{
                new FollowingChannelListRequest(), FollowingChannelListRequest.builder().build()}) {
            assertTrue(validator.validate(request).isEmpty());
            assertEquals(FollowingSortBy.FOLLOWER_COUNT, request.getSortBy());
            assertEquals(Sort.Direction.DESC, request.getSortDirection());
            assertEquals(0, request.getPage());
            assertEquals(20, request.getSize());
        }
    }

    @Test
    void acceptsBothBusinessSortsBothDirectionsAndPaginationBoundaries() {
        for (FollowingSortBy sort : FollowingSortBy.values()) {
            for (Sort.Direction direction : Sort.Direction.values()) {
                assertTrue(validator.validate(FollowingChannelListRequest.builder()
                        .sortBy(sort).sortDirection(direction).page(0).size(100).build()).isEmpty());
            }
        }
        assertTrue(validator.validate(FollowingChannelListRequest.builder()
                .page(Integer.MAX_VALUE).size(1).build()).isEmpty());
    }

    @ParameterizedTest
    @MethodSource("invalidRequests")
    void rejectsInvalidRequestFields(FollowingChannelListRequest request, String property) {
        assertTrue(validator.validate(request).stream()
                .anyMatch(violation -> violation.getPropertyPath().toString().equals(property)));
    }

    private static Stream<Arguments> invalidRequests() {
        return Stream.of(
                Arguments.of(FollowingChannelListRequest.builder().sortBy(null).build(), "sortBy"),
                Arguments.of(FollowingChannelListRequest.builder().sortDirection(null).build(), "sortDirection"),
                Arguments.of(FollowingChannelListRequest.builder().page(null).build(), "page"),
                Arguments.of(FollowingChannelListRequest.builder().size(null).build(), "size"),
                Arguments.of(FollowingChannelListRequest.builder().page(-1).build(), "page"),
                Arguments.of(FollowingChannelListRequest.builder().size(0).build(), "size"),
                Arguments.of(FollowingChannelListRequest.builder().size(101).build(), "size"),
                Arguments.of(FollowingChannelListRequest.builder().page(Integer.MAX_VALUE).size(2).build(), "offsetValid"));
    }
}
