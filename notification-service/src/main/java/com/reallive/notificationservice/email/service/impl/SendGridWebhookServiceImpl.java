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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class SendGridWebhookServiceImpl implements SendGridWebhookService {

    static final int QUERY_BATCH_SIZE = 500;

    EmailDeliveryProperties properties;
    ObjectMapper objectMapper;
    EmailNotificationRepository notifications;
    EmailDeliveryEventRepository deliveryEvents;

    @Override
    @Transactional
    public void receive(byte[] body, String signature, String timestamp) {
        verifySignature(body, signature, timestamp);
        List<SendGridWebhookEventRequest> events = readEvents(body);
        if (events.isEmpty()) {
            return;
        }
        Map<UUID, EmailNotificationEntity> locked = lockNotifications(events);
        List<SendGridWebhookEventRequest> correlated = events.stream()
                .filter(event -> locked.containsKey(UUID.fromString(event.getEventId())))
                .toList();
        Set<String> seen = findExistingEventIds(correlated);
        List<EmailDeliveryEventEntity> pending = new ArrayList<>();
        for (SendGridWebhookEventRequest event : correlated) {
            if (seen.add(event.getSgEventId())) {
                pending.add(deliveryEvent(event, locked.get(UUID.fromString(event.getEventId()))));
            }
        }
        if (!pending.isEmpty()) {
            deliveryEvents.saveAllAndFlush(pending);
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

    /** Order chunks and database row locks identically to avoid deadlocks between overlapping batches. */
    private Map<UUID, EmailNotificationEntity> lockNotifications(List<SendGridWebhookEventRequest> events) {
        List<UUID> eventIds = events.stream().map(event -> UUID.fromString(event.getEventId()))
                .distinct().sorted(Comparator.comparing(UUID::toString)).toList();
        Map<UUID, EmailNotificationEntity> locked = new HashMap<>();
        for (int offset = 0; offset < eventIds.size(); offset += QUERY_BATCH_SIZE) {
            List<UUID> batch = eventIds.subList(offset, Math.min(offset + QUERY_BATCH_SIZE, eventIds.size()));
            notifications.findAllByEventIdInForUpdate(batch)
                    .forEach(notification -> locked.put(notification.getEventId(), notification));
        }
        return locked;
    }

    private Set<String> findExistingEventIds(List<SendGridWebhookEventRequest> events) {
        List<String> sgEventIds = events.stream().map(SendGridWebhookEventRequest::getSgEventId).distinct().toList();
        Set<String> existing = new HashSet<>();
        for (int offset = 0; offset < sgEventIds.size(); offset += QUERY_BATCH_SIZE) {
            List<String> batch = sgEventIds.subList(offset, Math.min(offset + QUERY_BATCH_SIZE, sgEventIds.size()));
            existing.addAll(deliveryEvents.findExistingSgEventIds(batch));
        }
        return existing;
    }

    private EmailDeliveryEventEntity deliveryEvent(SendGridWebhookEventRequest event, EmailNotificationEntity notification) {
        return EmailDeliveryEventEntity.builder()
                .notification(notification).sgEventId(event.getSgEventId())
                .sgMessageId(event.getSgMessageId()).eventType(EmailDeliveryEventType.fromSendGridEvent(event.getEvent()))
                .providerTimestamp(Instant.ofEpochSecond(event.getTimestamp()))
                .response(cleanDetail(event.getResponse())).reason(cleanDetail(event.getReason()))
                .smtpStatus(event.getStatus()).build();
    }

    private String cleanDetail(String detail) {
        if (detail == null) {
            return null;
        }
        String cleaned = detail.replaceAll("\\p{Cntrl}", " ");
        return cleaned.substring(0, Math.min(cleaned.length(), 2048));
    }
}
