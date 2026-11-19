package com.reallive.streamservice.client.user;

import com.reallive.streamservice.common.dto.ApiResponse;
import com.reallive.streamservice.common.exception.AppException;
import com.reallive.streamservice.common.exception.ErrorCode;
import feign.FeignException;
import feign.Request;
import feign.Response;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class UserClientTest {

    private final UserApiClient api = mock(UserApiClient.class);
    private final UserClient client = new UserClient(api);

    @Test
    void emptyUserIdsReturnEmptyMapWithoutCallingUserService() {
        assertThat(client.getViewerProfiles(null)).isEmpty();
        assertThat(client.getViewerProfiles(List.of())).isEmpty();
        verifyNoInteractions(api);
    }

    @Test
    void deduplicatesAndChunksRequestsToMaxFiftyIdsPerBatch() {
        List<UUID> uniqueIds = new ArrayList<>();
        for (int index = 0; index < 120; index++) {
            uniqueIds.add(UUID.randomUUID());
        }
        List<UUID> withDuplicates = new ArrayList<>(uniqueIds);
        withDuplicates.add(uniqueIds.getFirst());
        withDuplicates.add(uniqueIds.get(55));

        List<Integer> batchSizes = new ArrayList<>();
        when(api.getViewerProfiles(any())).thenAnswer(invocation -> {
            UserApiClient.ViewerProfileBatchRequest request = invocation.getArgument(0);
            batchSizes.add(request.userIds().size());
            List<ViewerProfile> result = request.userIds().stream()
                    .map(id -> new ViewerProfile(id, "User-" + id.toString().substring(0, 4), null, "user_" + id.toString().substring(0, 4)))
                    .toList();
            return ApiResponse.<List<ViewerProfile>>builder()
                    .status(200)
                    .code(1000)
                    .result(result)
                    .build();
        });

        Map<UUID, ViewerProfile> profiles = client.getViewerProfiles(withDuplicates);
        assertThat(batchSizes).containsExactly(50, 50, 20);
        assertThat(profiles).hasSize(120);
        verify(api, times(3)).getViewerProfiles(any());
    }

    @Test
    void rejectsMalformedOrUnexpectedProfilesAndUpstreamFailures() {
        UUID requested = UUID.randomUUID();
        when(api.getViewerProfiles(any())).thenReturn(ApiResponse.<List<ViewerProfile>>builder()
                .status(200)
                .code(1000)
                .result(List.of(new ViewerProfile(UUID.randomUUID(), "Other", null, "other")))
                .build());
        assertThatThrownBy(() -> client.getViewerProfiles(List.of(requested)))
                .isInstanceOfSatisfying(AppException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.STREAM_DATA_UNAVAILABLE));

        when(api.getViewerProfiles(any())).thenReturn(ApiResponse.<List<ViewerProfile>>builder()
                .status(200)
                .code(1000)
                .result(List.of(new ViewerProfile(requested, null, null, "user")))
                .build());
        assertThatThrownBy(() -> client.getViewerProfiles(List.of(requested)))
                .isInstanceOfSatisfying(AppException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.STREAM_DATA_UNAVAILABLE));

        Request dummyRequest = Request.create(Request.HttpMethod.POST, "/internal/api/users/chat-profiles",
                Map.of(), null, StandardCharsets.UTF_8, null);
        when(api.getViewerProfiles(any())).thenThrow(FeignException.errorStatus(
                "getViewerProfiles",
                Response.builder().status(503).request(dummyRequest).headers(Map.of()).build()));
        assertThatThrownBy(() -> client.getViewerProfiles(List.of(requested)))
                .isInstanceOfSatisfying(AppException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.STREAM_DATA_UNAVAILABLE));
    }
}
