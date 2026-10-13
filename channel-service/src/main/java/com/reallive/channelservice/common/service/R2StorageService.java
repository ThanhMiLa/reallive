package com.reallive.channelservice.common.service;

import com.reallive.channelservice.common.exception.AppException;

import java.util.UUID;

public interface R2StorageService {

    /**
     * Builds the public URL for an object key using the configured R2 public base URL.
     *
     * @param objectKey the storage key relative to the bucket root
     * @return the full public URL; this does not verify that the object exists
     */
    String getPublicUrl(String objectKey);

    /**
     * Uploads the supplied bytes to the configured R2 bucket.
     *
     * @param objectKey the destination storage key
     * @param content the bytes to upload
     * @param contentType the MIME type stored with the object
     * @throws AppException if the provider reports an upload failure
     */
    void upload(String objectKey, byte[] content, String contentType);

    /**
     * Attempts to delete an object while logging and suppressing provider SDK failures.
     *
     * @param objectKey the storage key to delete
     */
    void deleteQuietly(String objectKey);

    /**
     * Attempts to delete a managed banner belonging to the supplied channel.
     *
     * <p>Null URLs, external URLs and keys outside the channel's managed banner path are ignored.
     * Provider SDK deletion failures are logged and suppressed.</p>
     *
     * @param bannerUrl the previous public banner URL, which may be null
     * @param channelId the channel that owns the managed banner
     */
    void deleteManagedBanner(String bannerUrl, UUID channelId);
}
