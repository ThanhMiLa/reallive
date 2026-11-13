package com.reallive.streamservice.stream.scheduler;

import com.reallive.streamservice.stream.MediaTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
        "stream.thumbnail.enabled=true",
        "stream.thumbnail.scheduler-delay=1h",
        "stream.thumbnail.worker-base-url=http://127.0.0.1:8090",
        "stream.thumbnail.internal-hls-base-url=http://127.0.0.1:8888",
        "stream.lifecycle.reconnect-sweep-enabled=true",
        "stream.lifecycle.reconnect-sweep-delay=1h"
})
@ActiveProfiles("test")
class ThumbnailSchedulerEnabledContextTest {

    @DynamicPropertySource
    static void media(DynamicPropertyRegistry registry) {
        MediaTestSupport.register(registry);
    }

    @Autowired
    private ApplicationContext context;

    @Test
    void enablesTheThumbnailSchedulerWithItsOwnExecutorAlongsideReconnectScheduling() throws Exception {
        assertNotNull(context.getBean(ThumbnailDispatchScheduler.class));
        assertNotNull(context.getBean(ReconnectExpirationScheduler.class));
        assertTrue(context.containsBean("thumbnailTaskScheduler"));

        TaskScheduler reconnectScheduler = context.getBean("reconnectTaskScheduler", TaskScheduler.class);
        TaskScheduler thumbnailScheduler = context.getBean("thumbnailTaskScheduler", TaskScheduler.class);
        assertSame(thumbnailScheduler, ReflectionTestUtils.getField(
                context.getBean(ThumbnailDispatchScheduler.class), "thumbnailTaskScheduler"));
        assertNotSame(reconnectScheduler, thumbnailScheduler);
        ThreadPoolTaskScheduler reconnectPool = assertInstanceOf(ThreadPoolTaskScheduler.class, reconnectScheduler);
        ThreadPoolTaskScheduler thumbnailPool = assertInstanceOf(ThreadPoolTaskScheduler.class, thumbnailScheduler);
        assertEquals("reconnect-scheduler-", reconnectPool.getThreadNamePrefix());
        assertEquals("thumbnail-scheduler-", thumbnailPool.getThreadNamePrefix());
        assertEquals(1, reconnectPool.getScheduledThreadPoolExecutor().getCorePoolSize());
        assertEquals(1, thumbnailPool.getScheduledThreadPoolExecutor().getCorePoolSize());

        Scheduled thumbnailPass = ThumbnailDispatchScheduler.class
                .getDeclaredMethod("dispatchOnSchedule")
                .getAnnotation(Scheduled.class);
        Scheduled reconnectPass = ReconnectExpirationScheduler.class
                .getDeclaredMethod("sweepExpiredReconnections")
                .getAnnotation(Scheduled.class);
        assertEquals("thumbnailTaskScheduler", thumbnailPass.scheduler());
        assertEquals("reconnectTaskScheduler", reconnectPass.scheduler());
    }
}
