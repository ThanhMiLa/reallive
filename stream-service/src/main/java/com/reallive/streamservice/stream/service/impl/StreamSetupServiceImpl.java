package com.reallive.streamservice.stream.service.impl;

import com.reallive.streamservice.common.configuration.MediaProperties;
import com.reallive.streamservice.common.exception.AppException;
import com.reallive.streamservice.common.exception.ErrorCode;
import com.reallive.streamservice.stream.adapter.media.MediaJwtIssuer;
import com.reallive.streamservice.stream.adapter.media.MediaServerAdapter;
import com.reallive.streamservice.stream.dto.request.SaveStreamSetupRequest;
import com.reallive.streamservice.stream.dto.response.StreamKeyResponse;
import com.reallive.streamservice.stream.dto.response.StreamSetupResponse;
import com.reallive.streamservice.stream.entity.ChannelStreamConfigEntity;
import com.reallive.streamservice.stream.entity.enums.ChannelStreamConfigStatus;
import com.reallive.streamservice.stream.entity.enums.StreamVisibility;
import com.reallive.streamservice.stream.repository.ChannelStreamConfigRepository;
import com.reallive.streamservice.stream.repository.StreamSessionRepository;
import com.reallive.streamservice.stream.service.StreamEligibilityService;
import com.reallive.streamservice.stream.service.StreamSetupService;
import com.reallive.streamservice.stream.util.StreamConstraintViolations;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class StreamSetupServiceImpl implements StreamSetupService {
    ChannelStreamConfigRepository configs;
    StreamSessionRepository sessions;
    StreamEligibilityService eligibility;
    MediaServerAdapter media;
    MediaJwtIssuer issuer;
    MediaProperties properties;
    StreamSessionLifecycleSupport lifecycle;
    PlatformTransactionManager transactionManager;

    @Override
    public StreamSetupResponse getOrCreateSetup(UUID ownerUserId) {
        var context = eligibility.requireChannelContext(ownerUserId);
        if (configs.findById(context.channelId()).isEmpty()) {
            try {
                new TransactionTemplate(transactionManager)
                        .executeWithoutResult(
                                status ->
                                        configs.saveAndFlush(
                                                ChannelStreamConfigEntity.builder()
                                                        .channelId(context.channelId())
                                                        .streamerId(context.streamerId())
                                                        .ownerUserId(ownerUserId)
                                                        .title("")
                                                        .visibility(StreamVisibility.PUBLIC)
                                                        .status(ChannelStreamConfigStatus.DRAFT)
                                                        .build()));
            } catch (DataIntegrityViolationException exception) {
                if (!StreamConstraintViolations.isUniqueViolation(exception)) throw exception;
            }
        }
        return response(
                configs.findByOwnerUserId(ownerUserId)
                        .orElseThrow(() -> new AppException(ErrorCode.STREAM_DATA_UNAVAILABLE)));
    }

    @Override
    public StreamSetupResponse saveSetup(UUID ownerUserId, SaveStreamSetupRequest request) {
        if (request == null
                || request.title() == null
                || request.title().isBlank()
                || request.title().length() > 255
                || request.categoryId() == null) {
            throw new AppException(ErrorCode.STREAM_REQUEST_INVALID);
        }
        eligibility.requirePublishContext(ownerUserId, request.categoryId());
        getOrCreateSetup(ownerUserId);
        return new TransactionTemplate(transactionManager)
                .execute(
                        status -> {
                            var config = ownedConfig(ownerUserId);
                            config.setTitle(request.title().trim());
                            config.setCategoryId(request.categoryId());
                            config.setBlockedReason(null);
                            config.setStatus(
                                    config.getCurrentIngestKeyId() == null
                                            ? ChannelStreamConfigStatus.DRAFT
                                            : ChannelStreamConfigStatus.ACTIVE);
                            configs.saveAndFlush(config);
                            sessions.findSlotOccupyingByChannelIdForUpdate(config.getChannelId())
                                    .ifPresent(
                                            session -> {
                                                session.setTitle(config.getTitle());
                                                session.setCategoryId(config.getCategoryId());
                                                sessions.saveAndFlush(session);
                                            });
                            return response(config);
                        });
    }

    @Override
    public StreamKeyResponse issueStreamKey(UUID ownerUserId) {
        var snapshot =
                configs.findByOwnerUserId(ownerUserId)
                        .orElseThrow(() -> new AppException(ErrorCode.STREAM_KEY_NOT_PROVISIONED));
        if (snapshot.getCategoryId() == null || snapshot.getTitle().isBlank()) {
            throw new AppException(ErrorCode.STREAM_REQUEST_INVALID);
        }
        eligibility.requirePublishContext(ownerUserId, snapshot.getCategoryId());
        return new TransactionTemplate(transactionManager)
                .execute(
                        status -> {
                            var config = ownedConfig(ownerUserId);
                            if (config.getStatus() == ChannelStreamConfigStatus.BLOCKED) {
                                throw new AppException(ErrorCode.STREAM_CONFIG_BLOCKED);
                            }
                            sessions.findSlotOccupyingByChannelIdForUpdate(config.getChannelId())
                                    .ifPresent(
                                            session -> {
                                                lifecycle.closePlaybackPathAndVerify(
                                                        session.getId());
                                                lifecycle.finalizeEndedSession(
                                                        config,
                                                        session,
                                                        lifecycle.nowUtc(),
                                                        "Stream key replaced",
                                                        null,
                                                        null);
                                            });
                            if (config.getCurrentIngestKeyId() != null) {
                                lifecycle.disconnectPublisherAndDeleteIngestAndVerify(
                                        config.getCurrentIngestKeyId(), null, null, true);
                            }
                            UUID newIngestKeyId = UUID.randomUUID();
                            media.ensureIngestPath(newIngestKeyId);
                            config.setCurrentIngestKeyId(newIngestKeyId);
                            config.setStatus(ChannelStreamConfigStatus.ACTIVE);
                            configs.saveAndFlush(config);
                            String token = issuer.issuePublish(newIngestKeyId, ownerUserId).value();
                            return new StreamKeyResponse(
                                    config.getChannelId(),
                                    properties.getRtmpPublicUrl(),
                                    issuer.formatStreamKey(newIngestKeyId, token));
                        });
    }

    private ChannelStreamConfigEntity ownedConfig(UUID ownerUserId) {
        return configs.findByOwnerUserIdForUpdate(ownerUserId)
                .orElseThrow(() -> new AppException(ErrorCode.STREAM_KEY_NOT_PROVISIONED));
    }

    private StreamSetupResponse response(ChannelStreamConfigEntity config) {
        return new StreamSetupResponse(
                config.getChannelId(),
                config.getTitle(),
                config.getCategoryId(),
                config.getVisibility(),
                config.getStatus(),
                config.getCurrentIngestKeyId() != null,
                config.getBlockedReason());
    }
}
