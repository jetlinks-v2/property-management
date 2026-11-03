package org.jetlinks.pro.property.base.service;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserSyncJobTest {
    @Test
    void manualTaskReturnsBeforeCompletionAndLifecycleOwnsSubscription() throws Exception {
        var service = mock(UserSyncService.class);
        var gate = Sinks.<UserSyncWriter.Result>one();
        var started = new CountDownLatch(1);
        when(service.acquire()).thenReturn(Mono.just("task"));
        when(service.execute("task"))
                .thenReturn(gate.asMono().doOnSubscribe(sub -> started.countDown()));
        var job = new UserSyncJob(service, true, 60);
        job.start();
        try {
            var task = job.submit().block(Duration.ofSeconds(3));
            assertEquals("task", task.taskId());
            assertTrue(task.running());
            assertTrue(started.await(3, TimeUnit.SECONDS));
            assertEquals(0, job.getSuccessCount());
            gate.tryEmitValue(new UserSyncWriter.Result(1, 0, 0, 123));
            assertEquals(1, job.getSuccessCount());
            assertEquals(123, job.getLastSuccessTime());
        } finally {
            job.stop();
        }
        assertFalse(job.isRunning());
        reactor.test.StepVerifier.create(job.submit()).expectError().verify();
    }
}
