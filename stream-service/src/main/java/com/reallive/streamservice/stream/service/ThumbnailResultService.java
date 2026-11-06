package com.reallive.streamservice.stream.service;

import com.reallive.streamservice.stream.dto.request.ThumbnailResultRequest;
import com.reallive.streamservice.stream.dto.response.ThumbnailResultResponse;

public interface ThumbnailResultService {

    /**
     * Applies one worker callback under a row lock and reports whether it changed the session.
     *
     * <p>A structurally valid result is ignored when the session is missing, the job identifier no
     * longer matches, the job is no longer waiting, the session is not live or the job deadline has
     * expired. An applied result stores the public URL, object key, backend UTC update time and
     * {@code SUCCEEDED} status atomically before the response is returned.</p>
     *
     * @param request validated callback payload from the media worker
     * @return {@code APPLIED} when the thumbnail was updated, or {@code IGNORED} when it was not
     * @throws com.reallive.streamservice.common.exception.AppException with
     *         {@code THUMBNAIL_RESULT_INVALID} when object-key or image metadata is invalid
     */
    ThumbnailResultResponse apply(ThumbnailResultRequest request);
}
