package com.reallive.notificationservice.email.dto.response;

import com.reallive.notificationservice.email.entity.EmailNotificationStatus;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;

import java.time.Instant;

/** Service-level submission outcome; Mail Send success has no response JSON body. */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class SendGridSubmissionResult {
    EmailNotificationStatus status;
    Short httpStatus;
    String providerMessageId;
    String safeError;
    Instant retryAt;
}
