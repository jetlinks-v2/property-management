package org.jetlinks.pro.property.quality.service;

import org.hswebframework.ezorm.rdb.mapping.defaults.record.Record;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.crud.query.QueryHelper;
import org.jetlinks.pro.property.quality.asset.QualityAssetType;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class QualityHomeAggregationTest {
    @Test
    void emptyAndPartialProjectsPreserveUnknownRatesAndLegacyRounding() {
        assertNull(QualityHomeAggregation.result(null, null, 0).get("qualifiedRate"));
        var tasks = Record.newRecord(Map.of("n0", 3L, "v0", 2L));
        var records = Record.newRecord(Map.of("n0", 3L, "v0", 1L));
        assertEquals(66.67, QualityHomeAggregation.result(tasks, records, 0).get("taskCompleteRate"));
        assertEquals(33.33, QualityHomeAggregation.result(tasks, records, 0).get("qualifiedRate"));
    }

    @Test
    void eightWindowsUseTwoQueriesWithScopedTermsAndKeepEmptyFutureBuckets() {
        var scope = mock(QualityScope.class);
        var reports = mock(QualityReports.class);
        var helper = mock(QueryHelper.class);
        var taskScope = QueryParamEntity.newQuery().where("applicationId", "app-a").and("projectId", "project-a").getParam();
        var recordScope = QueryParamEntity.newQuery().where("workOrderId", "allowed-order").getParam();
        when(scope.query(any(), eq(QualityAssetType.qualityTask), eq("id"))).thenReturn(Mono.just(taskScope));
        when(reports.recordQuery(any(), any())).thenReturn(Mono.just(recordScope));
        QueryHelper.NativeQuerySpec<Record> spec = mock(QueryHelper.NativeQuerySpec.class);
        when(helper.select(anyString(), any(Object[].class))).thenReturn(spec);
        when(spec.where(any(QueryParamEntity.class))).thenReturn(spec);
        when(spec.fetch()).thenReturn(Flux.just(Record.newRecord(Map.of("n0", 2L, "v0", 1L))));
        var windows = java.util.stream.IntStream.range(0, 8)
            .mapToObj(i -> new QualityHomeRules.Window("bucket", i * 10, i == 7 ? 0 : (i + 1) * 10)).toList();
        StepVerifier.create(new QualityHomeAggregation(scope, reports, helper).windows("project-a", windows, true))
            .assertNext(rows -> {
                assertEquals(8, rows.size());
                assertEquals(50.0, rows.get(0).get("qualifiedRate"));
                assertNull(rows.get(7).get("qualifiedRate"));
            }).verifyComplete();
        verify(scope).query(any(), eq(QualityAssetType.qualityTask), eq("id"));
        verify(reports).recordQuery(any(), any());
        verify(helper, times(2)).select(anyString(), any(Object[].class));
        verify(spec).where(same(taskScope));
        verify(spec).where(same(recordScope));
        assertFalse(taskScope.isPaging()); assertTrue(taskScope.getSorts().isEmpty());
    }

    @Test
    void authorizationFailureDoesNotRunUnscopedSql() {
        var scope = mock(QualityScope.class); var reports = mock(QualityReports.class); var helper = mock(QueryHelper.class);
        when(scope.query(any(), any(), anyString())).thenReturn(Mono.error(new IllegalStateException("permission denied")));
        when(reports.recordQuery(any(), any())).thenReturn(Mono.just(new QueryParamEntity()));
        StepVerifier.create(new QualityHomeAggregation(scope, reports, helper).projects(null, 0, 10))
            .expectErrorMessage("permission denied").verify();
        verifyNoInteractions(helper);
    }
}
