package org.jetlinks.pro.property.base.service;

import org.jetlinks.pro.property.base.space.SpaceSyncResult;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class SpaceSyncJobTest {
    @Test
    void slowSynchronizationDoesNotTerminatePeriodicTaskOrAccumulateTicks() {
        var sync = mock(SpaceSyncService.class);
        when(sync.synchronize())
                .thenAnswer(
                        call ->
                                Mono.delay(Duration.ofSeconds(12))
                                        .map(ignored -> new SpaceSyncResult(0, 0, 0, 1L)));
        var job = new SpaceSyncJob(sync, Duration.ofSeconds(5));
        try {
            StepVerifier.withVirtualTime(
                            () -> {
                                job.start();
                                return Mono.delay(Duration.ofSeconds(48));
                            })
                    .thenAwait(Duration.ofSeconds(48))
                    .expectNext(0L)
                    .verifyComplete();
            assertTrue(job.getSuccessCount() >= 2, "慢同步后仍应持续运行");
            verify(sync, atLeast(3)).synchronize();
        } finally {
            job.stop();
        }
    }
}
