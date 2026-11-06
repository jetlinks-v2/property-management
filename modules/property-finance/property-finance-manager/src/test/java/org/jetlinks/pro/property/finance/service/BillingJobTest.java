package org.jetlinks.pro.property.finance.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.jetlinks.pro.property.finance.setting.BillingTaskKind;
import org.junit.jupiter.api.Test;

import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.time.ZonedDateTime;

class BillingJobTest {
    @Test
    void dailyTimesDefaultCloseOffAndRestartCatchupNeverRunBeforeDue() {
        var service = mock(BillingTaskService.class);
        when(service.resume()).thenReturn(Mono.just(new BillingTaskService.RunSummary(0, 0, 0)));
        when(service.scan(any(), any()))
                .thenReturn(Mono.just(new BillingTaskService.RunSummary(2, 0, 0)));
        var properties = new BillingJobProperties();
        properties.setZone("Asia/Shanghai");
        var job = new BillingJob(service, properties);
        var morning = ZonedDateTime.parse("2024-02-15T00:05:00+08:00[Asia/Shanghai]");
        job.poll(morning).block();
        verify(service).scan(BillingTaskKind.GENERATE, LocalDate.of(2024, 2, 15));
        verify(service, never()).scan(eq(BillingTaskKind.ACCRUE), any());
        job.poll(morning.plusMinutes(30)).block();
        verify(service).scan(BillingTaskKind.ACCRUE, LocalDate.of(2024, 2, 15));
        verify(service).scan(BillingTaskKind.LATE_FEE, LocalDate.of(2024, 2, 15));
        verify(service, never()).scan(eq(BillingTaskKind.CLOSE), any());
        job.poll(morning.plusHours(1)).block();
        verify(service, times(3)).scan(any(), any());
        assertEquals(6, job.getSuccessCount());
        new BillingJob(service, properties).poll(morning.plusHours(5)).block();
        verify(service, times(6)).scan(any(), any());
    }

    @Test
    void scanFailureRemainsDueAndIsVisibleOnMonitorInsteadOfMarkingCompleted() {
        var service = mock(BillingTaskService.class);
        when(service.resume()).thenReturn(Mono.just(new BillingTaskService.RunSummary(0, 0, 0)));
        when(service.scan(any(), any()))
                .thenReturn(
                        Mono.error(new IllegalStateException("broken")),
                        Mono.just(new BillingTaskService.RunSummary(1, 1, 0)));
        var job = new BillingJob(service, new BillingJobProperties());
        var date = ZonedDateTime.now().withHour(0).withMinute(1).withSecond(0);
        assertThrows(IllegalStateException.class, () -> job.poll(date).block());
        assertEquals(1, job.getFailureCount());
        assertFalse(job.getLastError().isBlank());
        job.poll(date).block();
        verify(service, times(2)).scan(eq(BillingTaskKind.GENERATE), any());
        assertEquals(1, job.getSuccessCount());
        assertEquals(2, job.getFailureCount());
    }
}
