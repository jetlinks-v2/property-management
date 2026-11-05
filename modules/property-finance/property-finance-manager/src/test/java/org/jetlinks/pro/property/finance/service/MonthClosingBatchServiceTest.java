package org.jetlinks.pro.property.finance.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.jetlinks.pro.property.finance.entity.AccountingPeriodEntity;
import org.junit.jupiter.api.Test;

import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.*;

class MonthClosingBatchServiceTest {
    @Test
    void executionStopsAfterFailureAndKeepsPriorCompletion() {
        var closing = mock(MonthClosingService.class);
        @SuppressWarnings("unchecked")
        ReactiveRepository<AccountingPeriodEntity, String> periods = mock(ReactiveRepository.class);
        var batch = spy(new MonthClosingBatchService(closing, periods));
        var input =
                new MonthClosingBatchService.Input(
                        List.of(
                                new MonthClosingBatchService.Item("2024-03", 0L, "request-3"),
                                new MonthClosingBatchService.Item("2024-01", 0L, "request-1"),
                                new MonthClosingBatchService.Item("2024-02", 0L, "request-2")),
                        "核对");
        var date = LocalDate.of(2024, 3, 31);
        doReturn(
                        Mono.just(
                                List.of(
                                        new MonthClosingBatchService.Row(
                                                "2024-01", "READY", null, null),
                                        new MonthClosingBatchService.Row(
                                                "2024-02", "READY", null, null),
                                        new MonthClosingBatchService.Row(
                                                "2024-03", "READY", null, null))))
                .when(batch)
                .preview("p", "CLOSE", input, date);
        when(closing.batchReplay(
                        anyString(), anyString(), anyString(), anyString(), anyLong(), anyString()))
                .thenReturn(Mono.empty());
        when(closing.preview(eq("p"), anyString(), eq("CLOSE"), eq(date)))
                .thenAnswer(
                        call ->
                                Mono.just(
                                        new MonthClosingService.Preview(
                                                "token",
                                                0L,
                                                call.getArgument(1),
                                                "CLOSE",
                                                "0.00",
                                                "0.00",
                                                0,
                                                0,
                                                0,
                                                0,
                                                0,
                                                false)));
        when(closing.submit(eq("p"), eq("2024-01"), eq("CLOSE"), any(), eq(date)))
                .thenReturn(
                        Mono.just(
                                new MonthClosingService.Result(
                                        "op",
                                        "2024-01",
                                        "CLOSED",
                                        1L,
                                        "2024-01-31",
                                        "0.00",
                                        "0.00",
                                        0,
                                        0)));
        when(closing.submit(eq("p"), eq("2024-02"), eq("CLOSE"), any(), eq(date)))
                .thenReturn(Mono.error(FinanceScope.error("version_conflict", 409)));
        var result = batch.execute("p", "CLOSE", input, date).block();
        assertEquals(
                List.of("COMPLETED", "FAILED", "NOT_EXECUTED"),
                result.stream().map(MonthClosingBatchService.Row::status).toList());
        verify(closing, never()).preview("p", "2024-03", "CLOSE", date);
        verify(closing, never()).submit(eq("p"), eq("2024-03"), anyString(), any(), any());
    }
}
