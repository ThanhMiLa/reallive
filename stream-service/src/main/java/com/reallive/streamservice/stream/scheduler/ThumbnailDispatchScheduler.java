package com.reallive.streamservice.stream.scheduler;

import com.reallive.streamservice.stream.service.ThumbnailDispatchService;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
@ConditionalOnProperty(name = "stream.thumbnail.enabled", havingValue = "true")
public class ThumbnailDispatchScheduler {

    ThumbnailDispatchService dispatchService;
    TaskScheduler thumbnailTaskScheduler;
    AtomicBoolean running = new AtomicBoolean();

    @EventListener(ApplicationReadyEvent.class)
    public void dispatchOnApplicationReady() {
        try {
            thumbnailTaskScheduler.schedule(this::runIfIdle, Instant.now());
        } catch (TaskRejectedException exception) {
            log.warn("Thumbnail scheduler rejected the application-ready dispatch");
        }
    }

    @Scheduled(
            fixedDelayString = "${stream.thumbnail.scheduler-delay:10s}",
            initialDelayString = "${stream.thumbnail.scheduler-delay:10s}",
            scheduler = "thumbnailTaskScheduler"
    )
    public void dispatchOnSchedule() {
        runIfIdle();
    }

    void runIfIdle() {
        if (!running.compareAndSet(false, true)) {
            log.debug("Skipping thumbnail dispatch because the previous pass is still running");
            return;
        }
        try {
            dispatchService.dispatchDueJobs();
        } catch (RuntimeException exception) {
            log.error("Thumbnail dispatch pass failed; the next scheduled pass will continue", exception);
        } finally {
            running.set(false);
        }
    }
}
