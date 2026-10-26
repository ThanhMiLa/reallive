package com.reallive.streamservice.stream.client;

import com.reallive.streamservice.common.dto.ApiResponse;
import com.reallive.streamservice.common.exception.AppException;
import com.reallive.streamservice.common.exception.ErrorCode;
import feign.FeignException;
import java.util.Set;
import java.util.function.Supplier;

public final class ClientResponses {
    private ClientResponses() {
    }

    public static <T> T read(Supplier<ApiResponse<T>> request, ErrorCode rejected, Set<Integer> rejectionStatuses) {
        try {
            ApiResponse<T> response = request.get();
            if (response == null || response.getStatus() != 200 || response.getCode() != 1000 || response.getResult() == null) {
                throw new AppException(ErrorCode.STREAM_DATA_UNAVAILABLE);
            }
            return response.getResult();
        } catch (FeignException exception) {
            throw new AppException(rejectionStatuses.contains(exception.status()) ? rejected : ErrorCode.STREAM_DATA_UNAVAILABLE);
        }
    }
}
