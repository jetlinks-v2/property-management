package org.jetlinks.pro.smartpark.handover.export;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;

import reactor.core.publisher.Mono;
import reactor.test.scheduler.VirtualTimeScheduler;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

class HandoverExportWorkerTest {
    @Test
    void workerWaitsForItsOwnTaskWithoutBlockingOtherTimersAndCancelsOnStop() {
        var scheduler = VirtualTimeScheduler.getOrSet();
        var service = spy(new HandoverExportService(null, null, null, null, null));
        var cancelled = new AtomicBoolean();
        var otherTimerFired = new AtomicBoolean();
        doReturn(Mono.never().doOnCancel(() -> cancelled.set(true)).then())
                .when(service).tick();

        try {
            service.start();
            service.start();
            Mono.delay(Duration.ofSeconds(6), scheduler)
                    .subscribe(ignored -> otherTimerFired.set(true));
            scheduler.advanceTimeBy(Duration.ofMinutes(1));
            verify(service).tick();
            assertTrue(service.isRunning());
            assertTrue(otherTimerFired.get());
            assertFalse(cancelled.get());

            service.stop();
            scheduler.advanceTimeBy(Duration.ofMinutes(1));
            assertTrue(cancelled.get());
            assertFalse(service.isRunning());
            verify(service).tick();
        } finally {
            service.stop();
            VirtualTimeScheduler.reset();
        }
    }
}
