package com.reallive.streamservice.client.thumbnail;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

@FeignClient(
        name = "media-worker",
        url = "${stream.thumbnail.worker-base-url:http://localhost:8090}",
        configuration = ThumbnailWorkerClientConfiguration.class
)
public interface ThumbnailWorkerApiClient {

    @PostMapping(value = "/internal/api/thumbnail-jobs/batch", consumes = MediaType.APPLICATION_JSON_VALUE)
    ThumbnailBatchResponse dispatchBatch(
            @RequestHeader("X-Internal-Token") String internalToken,
            @RequestBody ThumbnailBatchRequest request
    );
}
