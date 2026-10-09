package com.reallive.notificationservice.email.client.sendgrid.dto.request;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.FieldDefaults;

import java.util.List;
import java.util.Map;

/** Dynamic-template Mail Send payload; credentials are supplied through HTTP headers. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@FieldDefaults(level = AccessLevel.PRIVATE)
public class SendGridMailRequest {
    EmailAddress from;

    @JsonProperty("template_id")
    String templateId;

    List<Personalization> personalizations;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @FieldDefaults(level = AccessLevel.PRIVATE)
    public static class EmailAddress {
        String email;
        String name;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @FieldDefaults(level = AccessLevel.PRIVATE)
    public static class Personalization {
        List<EmailAddress> to;

        @JsonProperty("dynamic_template_data")
        OtpTemplateData dynamicTemplateData;

        @JsonProperty("custom_args")
        Map<String, String> customArgs;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @FieldDefaults(level = AccessLevel.PRIVATE)
    public static class OtpTemplateData {
        @ToString.Exclude
        String otp;

        int expiresInMinutes;
    }
}
