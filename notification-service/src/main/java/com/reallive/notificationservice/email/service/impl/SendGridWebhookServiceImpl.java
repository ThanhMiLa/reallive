package com.reallive.notificationservice.email.service.impl;

import com.reallive.notificationservice.common.exception.AppException;
import com.reallive.notificationservice.common.exception.ErrorCode;
import com.reallive.notificationservice.email.configuration.EmailDeliveryProperties;
import com.reallive.notificationservice.email.dto.request.SendGridWebhookEventRequest;
import com.reallive.notificationservice.email.entity.EmailDeliveryEventEntity;
import com.reallive.notificationservice.email.entity.EmailDeliveryEventType;
import com.reallive.notificationservice.email.entity.EmailNotificationEntity;
import com.reallive.notificationservice.email.repository.EmailDeliveryEventRepository;
import com.reallive.notificationservice.email.repository.EmailNotificationRepository;
import com.reallive.notificationservice.email.service.SendGridWebhookService;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class SendGridWebhookServiceImpl implements SendGridWebhookService {

    EmailDeliveryProperties properties;
    ObjectMapper objectMapper;
    EmailNotificationRepository notifications;
    EmailDeliveryEventRepository deliveryEvents;

    @Override
    @Transactional
    public void receive(byte[] body, String signature, String timestamp) {
        verifySignature(body, signature, timestamp);
        List<SendGridWebhookEventRequest> events = readEvents(body);
        Map<UUID, EmailNotificationEntity> locked = lockNotifications(events);
        for (SendGridWebhookEventRequest event : events) {
            EmailNotificationEntity notification = locked.get(UUID.fromString(event.getEventId()));
            if (notification == null || deliveryEvents.existsBySgEventId(event.getSgEventId())) {
                continue;
            }
            deliveryEvents.saveAndFlush(EmailDeliveryEventEntity.builder()
                    .notification(notification).sgEventId(event.getSgEventId())
                    .sgMessageId(event.getSgMessageId()).eventType(EmailDeliveryEventType.fromSendGridEvent(event.getEvent()))
                    .providerTimestamp(Instant.ofEpochSecond(event.getTimestamp()))
                    .response(cleanDetail(event.getResponse())).reason(cleanDetail(event.getReason()))
                    .smtpStatus(event.getStatus()).build());
        }
    }

    private void verifySignature(byte[] body, String signature, String timestamp) {
        if (signature == null || signature.isBlank() || timestamp == null || !timestamp.matches("[0-9]{1,19}")) {
            throw new AppException(ErrorCode.EMAIL_WEBHOOK_UNAUTHENTICATED);
        }
        PublicKey publicKey;
        try {
            String configuredKey = properties.getSendgrid().getWebhookVerificationPublicKey();
            publicKey = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(
                    Base64.getDecoder().decode(configuredKey == null ? "" : configuredKey)));
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new AppException(ErrorCode.EMAIL_WEBHOOK_CONFIGURATION_INVALID);
        }
        try {
            Signature verifier = Signature.getInstance("SHA256withECDSA");
            verifier.initVerify(publicKey);
            verifier.update(timestamp.getBytes(StandardCharsets.UTF_8));
            verifier.update(body);
            if (!verifier.verify(Base64.getDecoder().decode(signature))) {
                throw new AppException(ErrorCode.EMAIL_WEBHOOK_UNAUTHENTICATED);
            }
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new AppException(ErrorCode.EMAIL_WEBHOOK_UNAUTHENTICATED);
        }
    }

    private List<SendGridWebhookEventRequest> readEvents(byte[] body) {
        try {
            SendGridWebhookEventRequest[] incoming = objectMapper.readValue(body, SendGridWebhookEventRequest[].class);
            if (incoming == null) {
                throw new AppException(ErrorCode.EMAIL_WEBHOOK_INVALID);
            }
            List<SendGridWebhookEventRequest> events = new ArrayList<>();
            for (SendGridWebhookEventRequest event : incoming) {
                if (event == null || event.getEvent() == null) {
                    throw new AppException(ErrorCode.EMAIL_WEBHOOK_INVALID);
                }
                if (EmailDeliveryEventType.fromSendGridEvent(event.getEvent()) == null || event.getEventId() == null
                        || event.getEventId().isBlank()) {
                    continue;
                }
                UUID eventId = UUID.fromString(event.getEventId());
                if (!eventId.toString().equalsIgnoreCase(event.getEventId()) || event.getTimestamp() == null
                        || event.getTimestamp() < 0 || event.getSgEventId() == null || event.getSgEventId().isBlank()
                        || (event.getStatus() != null && event.getStatus().length() > 50)) {
                    throw new AppException(ErrorCode.EMAIL_WEBHOOK_INVALID);
                }
                Instant.ofEpochSecond(event.getTimestamp());
                events.add(event);
            }
            return events;
        } catch (JacksonException | IllegalArgumentException | DateTimeException exception) {
            throw new AppException(ErrorCode.EMAIL_WEBHOOK_INVALID);
        }
    }

    /** Lock in a stable order to serialize redelivery without deadlocking overlapping batches. */
    private Map<UUID, EmailNotificationEntity> lockNotifications(List<SendGridWebhookEventRequest> events) {
        Map<UUID, EmailNotificationEntity> locked = new HashMap<>();
        events.stream().map(event -> UUID.fromString(event.getEventId())).distinct().sorted().forEach(eventId ->
                notifications.findByEventIdForUpdate(eventId).ifPresent(notification -> locked.put(eventId, notification)));
        return locked;
    }

    private String cleanDetail(String detail) {
        if (detail == null) {
            return null;
        }
        String cleaned = detail.replaceAll("\\p{Cntrl}", " ");
        return cleaned.substring(0, Math.min(cleaned.length(), 2048));
    }
}
