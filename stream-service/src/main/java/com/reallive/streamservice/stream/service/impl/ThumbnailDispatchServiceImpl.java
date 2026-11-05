package com.reallive.streamservice.stream.service.impl;

import com.reallive.streamservice.stream.adapter.media.MediaJwtIssuer;
import com.reallive.streamservice.stream.adapter.media.MediaToken;
import com.reallive.streamservice.stream.client.thumbnail.RejectedThumbnailJob;
import com.reallive.streamservice.stream.client.thumbnail.ThumbnailBatchResponse;
import com.reallive.streamservice.stream.client.thumbnail.ThumbnailJobPayload;
import com.reallive.streamservice.stream.client.thumbnail.ThumbnailWorkerClient;
import com.reallive.streamservice.stream.configuration.ThumbnailProperties;
import com.reallive.streamservice.stream.dto.ThumbnailDispatchJob;
import com.reallive.streamservice.stream.entity.StreamSessionEntity;
import com.reallive.streamservice.stream.entity.enums.StreamSessionStatus;
import com.reallive.streamservice.stream.entity.enums.ThumbnailJobStatus;
import com.reallive.streamservice.stream.repository.StreamSessionRepository;
import com.reallive.streamservice.stream.service.ThumbnailDispatchService;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
@Transactional(propagation = Propagation.NEVER)
public class ThumbnailDispatchServiceImpl implements ThumbnailDispatchService {

    StreamSessionRepository sessions;
    PlatformTransactionManager transactionManager;
    ThumbnailWorkerClient workerClient;
    ThumbnailProperties properties;
    MediaJwtIssuer jwtIssuer;
    Clock streamClock;

    @Override
    public int dispatchDueJobs() {
        LocalDateTime now = LocalDateTime.now(streamClock).truncatedTo(ChronoUnit.MICROS);
        int expired = expireOverdueJobs(now);

        List<ThumbnailDispatchJob> batch = new ArrayList<>(resendPendingJobs(now));
        int remaining = properties.getBatchSize() - batch.size();
        if (remaining > 0) {
            batch.addAll(createDueJobs(now, remaining));
        }
        if (batch.isEmpty()) {
            return 0;
        }

        List<ThumbnailJobPayload> payloads = preparePayloads(batch);
        if (payloads.isEmpty()) {
            return 0;
        }

        log.info("Dispatching {} thumbnail jobs after expiring {} overdue jobs", payloads.size(), expired);
        dispatchBatch(payloads);
        return payloads.size();
    }

    private int expireOverdueJobs(LocalDateTime now) {
        List<UUID> candidates = sessions.findExpiredThumbnailJobIds(now, PageRequest.of(0, properties.getBatchSize()));
        int expired = 0;
        for (UUID sessionId : candidates) {
            try {
                if (Boolean.TRUE.equals(transaction().execute(status -> expireIfStillDue(sessionId, now)))) {
                    expired++;
                }
            } catch (RuntimeException exception) {
                log.warn("Unable to expire thumbnail job for session {}: {}",
                        sessionId, exception.getClass().getSimpleName());
            }
        }
        return expired;
    }

    private boolean expireIfStillDue(UUID sessionId, LocalDateTime now) {
        StreamSessionEntity session = sessions.findByIdForUpdate(sessionId).orElse(null);
        if (session == null || !isWaiting(session.getThumbnailJobStatus())) {
            return false;
        }
        LocalDateTime deadline = session.getThumbnailJobDeadlineAt();
        if (deadline == null || deadline.isAfter(now)) {
            return false;
        }
        session.setThumbnailJobStatus(ThumbnailJobStatus.FAILED);
        sessions.saveAndFlush(session);
        return true;
    }

    private List<ThumbnailDispatchJob> resendPendingJobs(LocalDateTime now) {
        List<UUID> candidates = sessions.findPendingThumbnailDispatchIds(now, PageRequest.of(0, properties.getBatchSize()));
        return collectJobs(candidates, sessionId -> preparePendingJob(sessionId, now));
    }

    private Optional<ThumbnailDispatchJob> preparePendingJob(UUID sessionId, LocalDateTime now) {
        return transaction().execute(status -> {
            StreamSessionEntity session = sessions.findByIdForUpdate(sessionId).orElse(null);
            if (session == null || session.getStatus() != StreamSessionStatus.LIVE
                    || session.getThumbnailJobStatus() != ThumbnailJobStatus.PENDING_DISPATCH
                    || session.getThumbnailJobDeadlineAt() == null
                    || !session.getThumbnailJobDeadlineAt().isAfter(now)) {
                return Optional.empty();
            }
            return Optional.of(toDispatchJob(session));
        });
    }

    private List<ThumbnailDispatchJob> createDueJobs(LocalDateTime now, int limit) {
        LocalDateTime cutoff = now.minus(properties.getInterval());
        List<UUID> candidates = sessions.findDueThumbnailSessionIds(cutoff, limit);
        return collectJobs(candidates, sessionId -> createDueJob(sessionId, now, cutoff));
    }

    private Optional<ThumbnailDispatchJob> createDueJob(UUID sessionId, LocalDateTime now, LocalDateTime cutoff) {
        return transaction().execute(status -> {
            StreamSessionEntity session = sessions.findByIdForUpdate(sessionId).orElse(null);
            if (session == null || session.getStatus() != StreamSessionStatus.LIVE
                    || isWaiting(session.getThumbnailJobStatus())
                    || (session.getThumbnailUpdatedAt() != null
                    && session.getThumbnailUpdatedAt().isAfter(cutoff))) {
                return Optional.empty();
            }

            UUID jobId = UUID.randomUUID();
            LocalDateTime deadline = now.plus(properties.getJobDeadline());
            session.setThumbnailJobId(jobId);
            session.setThumbnailJobStatus(ThumbnailJobStatus.PENDING_DISPATCH);
            session.setThumbnailJobDeadlineAt(deadline);
            sessions.saveAndFlush(session);
            return Optional.of(toDispatchJob(session));
        });
    }

    private List<ThumbnailDispatchJob> collectJobs(
            List<UUID> candidates,
            JobPreparer preparer
    ) {
        List<ThumbnailDispatchJob> jobs = new ArrayList<>(candidates.size());
        for (UUID sessionId : candidates) {
            try {
                preparer.prepare(sessionId).ifPresent(jobs::add);
            } catch (RuntimeException exception) {
                log.warn("Unable to prepare thumbnail job for session {}: {}",
                        sessionId, exception.getClass().getSimpleName());
            }
        }
        return jobs;
    }

    private ThumbnailDispatchJob toDispatchJob(StreamSessionEntity session) {
        return new ThumbnailDispatchJob(
                session.getId(),
                session.getThumbnailJobId(),
                session.getPlaybackPath(),
                session.getThumbnailJobDeadlineAt()
        );
    }

    private List<ThumbnailJobPayload> preparePayloads(List<ThumbnailDispatchJob> jobs) {
        List<ThumbnailJobPayload> payloads = new ArrayList<>(jobs.size());
        for (ThumbnailDispatchJob job : jobs) {
            try {
                MediaToken token = jwtIssuer.issueWorkerRead(job.sessionId());
                payloads.add(new ThumbnailJobPayload(
                        job.jobId(),
                        job.sessionId(),
                        playbackUrl(job.playbackPath()),
                        token.value(),
                        token.expiresAt().toString(),
                        utc(job.deadlineAt()).toString()
                ));
            } catch (RuntimeException exception) {
                log.warn("Unable to prepare thumbnail credentials for job {} in session {}: {}",
                        job.jobId(), job.sessionId(), exception.getClass().getSimpleName());
            }
        }
        return payloads;
    }

    private void dispatchBatch(List<ThumbnailJobPayload> payloads) {
        workerClient.dispatchBatch(payloads)
                .ifPresent(response -> applyAcceptedJobs(payloads, response));
    }

    private void applyAcceptedJobs(List<ThumbnailJobPayload> payloads, ThumbnailBatchResponse response) {
        Set<UUID> batchJobIds = payloads.stream()
                .map(ThumbnailJobPayload::jobId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<UUID> rejectedJobIds = response.rejectedJobs() == null ? Set.of() : response.rejectedJobs().stream()
                .map(RejectedThumbnailJob::jobId)
                .collect(Collectors.toSet());
        Map<UUID, UUID> sessionByJobId = payloads.stream()
                .collect(Collectors.toMap(ThumbnailJobPayload::jobId, ThumbnailJobPayload::sessionId));

        Set<UUID> accepted = response.acceptedJobIds().stream()
                .filter(batchJobIds::contains)
                .filter(jobId -> !rejectedJobIds.contains(jobId))
                .collect(Collectors.toSet());
        for (UUID jobId : accepted) {
            UUID sessionId = sessionByJobId.get(jobId);
            try {
                transaction().executeWithoutResult(status -> acknowledgeIfStillPending(sessionId, jobId));
            } catch (RuntimeException exception) {
                log.warn("Unable to acknowledge thumbnail job {} in session {}: {}",
                        jobId, sessionId, exception.getClass().getSimpleName());
            }
        }
    }

    private void acknowledgeIfStillPending(UUID sessionId, UUID jobId) {
        StreamSessionEntity session = sessions.findByIdForUpdate(sessionId).orElse(null);
        if (session == null
                || !jobId.equals(session.getThumbnailJobId())
                || session.getThumbnailJobStatus() != ThumbnailJobStatus.PENDING_DISPATCH) {
            return;
        }
        session.setThumbnailJobStatus(ThumbnailJobStatus.ACCEPTED);
        sessions.saveAndFlush(session);
    }

    private boolean isWaiting(ThumbnailJobStatus status) {
        return status == ThumbnailJobStatus.PENDING_DISPATCH || status == ThumbnailJobStatus.ACCEPTED;
    }

    private String playbackUrl(String playbackPath) {
        String baseUrl = properties.getInternalHlsBaseUrl();
        String normalizedBase = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String normalizedPath = playbackPath.startsWith("/") ? playbackPath : "/" + playbackPath;
        return normalizedBase + normalizedPath;
    }

    private Instant utc(LocalDateTime value) {
        return value.toInstant(ZoneOffset.UTC);
    }

    private TransactionTemplate transaction() {
        return new TransactionTemplate(transactionManager);
    }

    @FunctionalInterface
    private interface JobPreparer {
        Optional<ThumbnailDispatchJob> prepare(UUID sessionId);
    }
}
