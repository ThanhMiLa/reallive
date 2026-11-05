package com.reallive.streamservice.stream.scheduler;

import com.reallive.streamservice.stream.service.ThumbnailDispatchService;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ThumbnailDispatchSchedulerTest {

    @Test
    void applicationReadySchedulesThePassOnTheDedicatedScheduler() {
        AtomicInteger calls = new AtomicInteger();
        ThumbnailDispatchService service = () -> {
            calls.incrementAndGet();
            return 1;
        };
        TaskScheduler scheduler = mock(TaskScheduler.class);
        AtomicReference<Runnable> scheduled = new AtomicReference<>();
        doAnswer(invocation -> {
            scheduled.set(invocation.getArgument(0));
            return null;
        }).when(scheduler).schedule(any(Runnable.class), any(Instant.class));

        ThumbnailDispatchScheduler thumbnailScheduler = new ThumbnailDispatchScheduler(service, scheduler);
        thumbnailScheduler.dispatchOnApplicationReady();

        assertEquals(0, calls.get());
        assertNotNull(scheduled.get());
        scheduled.get().run();
        assertEquals(1, calls.get());
    }

    @Test
    void fixedDelayPassUsesTheDedicatedSchedulerAndDefaultDelay() throws NoSuchMethodException {
        Scheduled scheduled = ThumbnailDispatchScheduler.class
                .getDeclaredMethod("dispatchOnSchedule")
                .getAnnotation(Scheduled.class);

        assertNotNull(scheduled);
        assertEquals("thumbnailTaskScheduler", scheduled.scheduler());
        assertEquals("${stream.thumbnail.scheduler-delay:10s}", scheduled.fixedDelayString());
        assertEquals("${stream.thumbnail.scheduler-delay:10s}", scheduled.initialDelayString());
    }

    @Test
    void skipsAnOverlappingPassAndRunsAgainAfterThePreviousPassFinishes() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        ThumbnailDispatchService service = () -> {
            calls.incrementAndGet();
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("First pass did not finish");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            return 1;
        };
        ThumbnailDispatchScheduler scheduler = new ThumbnailDispatchScheduler(service, mock(TaskScheduler.class));
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            Future<?> first = executor.submit(scheduler::runIfIdle);
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            scheduler.runIfIdle();
            assertEquals(1, calls.get());
            release.countDown();
            first.get(2, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }

        scheduler.runIfIdle();
        assertEquals(2, calls.get());
    }

    @Test
    void aFailedPassDoesNotStopLaterPasses() {
        AtomicInteger calls = new AtomicInteger();
        ThumbnailDispatchService service = () -> {
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("Simulated dispatch failure");
            }
            return 1;
        };
        ThumbnailDispatchScheduler scheduler = new ThumbnailDispatchScheduler(service, mock(TaskScheduler.class));

        assertDoesNotThrow(scheduler::runIfIdle);
        assertDoesNotThrow(scheduler::runIfIdle);
        assertEquals(2, calls.get());
    }

    @Test
    void applicationReadySurvivesSchedulerRejectionDuringShutdown() {
        TaskScheduler rejecting = mock(TaskScheduler.class);
        when(rejecting.schedule(any(Runnable.class), any(Instant.class)))
                .thenThrow(new TaskRejectedException("shutting down", new RejectedExecutionException()));
        ThumbnailDispatchScheduler scheduler = new ThumbnailDispatchScheduler(() -> 0, rejecting);

        assertDoesNotThrow(scheduler::dispatchOnApplicationReady);
    }
}
