package org.jetlinks.pro.property.finance.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.finance.overview.OverviewInput;
import org.junit.jupiter.api.Test;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Collections;
import java.util.List;

class FinanceOverviewTest {
    private OverviewInput input() {
        var input = new OverviewInput();
        input.setProjectIds(List.of("a", "b"));
        input.setStartDate("2024-01-01");
        input.setEndDate("2024-12-31");
        return input;
    }

    @Test
    void validatesStrictDatesProjectLimitAndTrendGrain() {
        var input = input();
        assertDoesNotThrow(input::validate);
        assertEquals("MONTH", input.grain());
        input.setEndDate("2025-01-01");
        assertThrows(RuntimeException.class, input::validate);
        for (var invalid : List.of("2024-02-30", "2024-1-1", "bad", "2023-12-31")) {
            input.setEndDate(invalid);
            assertThrows(RuntimeException.class, input::validate);
        }
        input.setEndDate("2024-01-01");
        assertEquals("DAY", input.grain());
        input.setProjectIds(Collections.nCopies(101, "a"));
        assertThrows(RuntimeException.class, input::validate);
        input.setProjectIds(List.of());
        assertThrows(RuntimeException.class, input::validate);
        input.setProjectIds(null);
        assertThrows(RuntimeException.class, input::validate);
        input.setProjectIds(List.of("a"));
        input.setStartDate(null);
        assertThrows(RuntimeException.class, input::validate);
    }

    @Test
    void currentBalancesAndHandoverDoNotInheritDateFilter() {
        var input = input();
        assertEquals("2024-01-01", input.cash(false).getReceivedDateStart());
        assertEquals("2024-12-31", input.cash(false).getReceivedDateEnd());
        assertNull(input.cash(true).getReceivedDateStart());
        assertNull(input.cash(true).getReceivedDateEnd());
        assertEquals(input.getProjectIds(), input.cash(true).getProjectIds());
        assertEquals("2024-01-01", input.receivables().getReceivableDateStart());
    }

    @Test
    void deniesWholeRequestBeforeBaseOrFinanceWhenOneProjectIsForbidden() {
        var scope = mock(FinanceScope.class);
        var houses = mock(HouseArchiveSource.class);
        var reports = mock(ReceivableReportService.class);
        when(scope.project("a", CrudAssetPermission.read)).thenReturn(Mono.empty());
        when(scope.project("b", CrudAssetPermission.read))
                .thenReturn(Mono.error(FinanceScope.error("test_denied", 403)));
        when(houses.summary(anyList()))
                .thenReturn(Mono.error(new AssertionError("must not subscribe")));
        var service = new FinanceOverviewService(scope, houses, reports, null, null, null);
        StepVerifier.create(service.query(input()))
                .expectErrorMatches(error -> TaxItemPersistenceTest.status(error, 403))
                .verify();
        verifyNoInteractions(reports);
    }

    @Test
    void upstreamFailureCannotTurnIntoZeroOrPartialStatistics() {
        var scope = mock(FinanceScope.class);
        var houses = mock(HouseArchiveSource.class);
        var reports = mock(ReceivableReportService.class);
        when(scope.project(anyString(), eq(CrudAssetPermission.read))).thenReturn(Mono.empty());
        when(houses.summary(anyList()))
                .thenReturn(Mono.error(FinanceScope.error("house_source_unavailable", 503)));
        var service = new FinanceOverviewService(scope, houses, reports, null, null, null);
        StepVerifier.create(service.query(input()))
                .expectErrorMatches(error -> TaxItemPersistenceTest.status(error, 503))
                .verify();
        verifyNoInteractions(reports);
    }
}
