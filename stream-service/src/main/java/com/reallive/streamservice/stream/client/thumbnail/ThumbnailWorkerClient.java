package com.reallive.streamservice.stream.client.thumbnail;

import com.reallive.streamservice.stream.configuration.ThumbnailProperties;
import feign.FeignException;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Dispatches thumbnail batches to the media worker.
 *
 * <p>HTTP, transport and malformed-response failures are logged and treated as an unacknowledged
 * batch so the scheduler resends the same jobs with their original IDs.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class ThumbnailWorkerClient {

    ThumbnailWorkerApiClient api;
    ThumbnailProperties properties;

    public Optional<ThumbnailBatchResponse> dispatchBatch(List<ThumbnailJobPayload> jobs) {
        try {
            ThumbnailBatchResponse response = api.dispatchBatch(
                    properties.getInternalToken(),
                    new ThumbnailBatchRequest(jobs)
            );
            if (response == null || response.acceptedJobIds() == null) {
                log.warn("Thumbnail batch response is missing acceptedJobIds; no job was acknowledged");
                return Optional.empty();
            }
            return Optional.of(response);
        } catch (FeignException exception) {
            log.warn("Thumbnail batch dispatch failed with HTTP status {}; jobs remain pending", exception.status());
            return Optional.empty();
        } catch (RuntimeException exception) {
            log.warn("Thumbnail batch dispatch failed with {}; jobs remain pending",
                    exception.getClass().getSimpleName());
            return Optional.empty();
        }
    }
}
