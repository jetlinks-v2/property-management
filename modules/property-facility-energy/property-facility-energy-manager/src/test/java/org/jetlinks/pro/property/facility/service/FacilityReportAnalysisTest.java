package org.jetlinks.pro.property.facility.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.util.*;
import org.hswebframework.ezorm.core.param.Term;
import org.hswebframework.ezorm.rdb.mapping.ReactiveQuery;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.jetlinks.pro.property.facility.asset.FacilityAssetType;
import org.jetlinks.pro.property.facility.entity.*;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.*;
import reactor.test.StepVerifier;

class FacilityReportAnalysisTest {
  @Test
  void workPeriodAndMainTaskRestrictionAreAppliedBeforeAssetQuery() {
    var tasks = mock(TaskStore.class); var groups = mock(GroupStore.class); var scope = mock(FacilityScope.class);
    when(scope.query(any(), any(), anyString())).thenAnswer(call -> Mono.just(call.getArgument(0)));
    bind(tasks, List.of()); bind(groups, List.of());
    var reports = new FacilityReportAnalysis(tasks, null, null, groups, scope, null);
    StepVerifier.create(reports.work("project", 1000, 2000, "maintenance")).assertNext(result -> assertEquals(0, result.get("total"))).verifyComplete();
    verify(scope).query(argThat(q -> contains(q.getTerms(), "projectId", "project") && contains(q.getTerms(), "kind", "maintenance") &&
      contains(q.getTerms(), "scheduledTime", 1000L) && contains(q.getTerms(), "scheduledTime", 2000L) && contains(q.getTerms(), "parentTaskId", null)), eq(FacilityAssetType.facilityTask), eq("id"));
  }

  @Test
  void excessiveAggregationFailsExplicitlyInsteadOfReturningPartialTotals() {
    var tasks = mock(TaskStore.class); var groups = mock(GroupStore.class); var scope = mock(FacilityScope.class);
    when(scope.query(any(), any(), anyString())).thenAnswer(call -> Mono.just(call.getArgument(0)));
    bind(tasks, Collections.nCopies(20001, new TaskEntity())); bind(groups, List.of());
    var reports = new FacilityReportAnalysis(tasks, null, null, groups, scope, null);
    StepVerifier.create(reports.work("project", 1000, 2000, "inspection"))
      .expectErrorMatches(error -> "error.facility.too_many".equals(error.getMessage())).verify();
  }

  @Test
  void historicalEpisodesUseBothParentAssetScopesAndKeepPagingEnabled() {
    var store = mock(AlertEpisodeStore.class); var scope = mock(FacilityScope.class);
    when(scope.query(any(), any(), anyString())).thenAnswer(call -> Mono.just(call.getArgument(0)));
    when(store.queryPager(any(QueryParamEntity.class))).thenAnswer(call -> {
      QueryParamEntity request = call.getArgument(0);
      assertTrue(request.isPaging()); assertEquals(200, request.getPageSize());
      assertEquals(Term.Type.or, request.getTerms().get(1).getType());
      assertTrue(contains(request.getTerms(), "projectId", "project"));
      assertTrue(contains(request.getTerms(), "deviceId", null)); assertTrue(contains(request.getTerms(), "targetId", null));
      assertTrue(contains(request.getTerms(), "firstTime", 1000L));
      return Mono.just(org.hswebframework.web.api.crud.entity.PagerResult.of(0, List.<AlertEpisodeEntity>of(), request));
    });
    var input = new QueryParamEntity(); input.setPaging(false); input.setPageSize(5000);
    var reports = new FacilityReportAnalysis(null, null, store, null, scope, null);
    StepVerifier.create(reports.alertDetails("project", 1000, 2000, input)).expectNextCount(1).verifyComplete();
    verify(scope).query(any(), eq(FacilityAssetType.facilityDevice), eq("deviceId"));
    verify(scope).query(any(), eq(FacilityAssetType.facilityTarget), eq("targetId"));
  }

  private static boolean contains(List<Term> terms, String column, Object value) {
    return terms.stream().anyMatch(term -> column.equals(term.getColumn()) && (value == null ?
      ("parentTaskId".equals(column) ? "isnull" : "notnull").equals(term.getTermType()) : Objects.equals(value, term.getValue())) ||
      term.getTerms() != null && contains(term.getTerms(), column, value));
  }
  @SuppressWarnings("unchecked")
  private static <T extends FacilityEntity> void bind(org.hswebframework.web.crud.service.GenericReactiveCrudService<T, String> store, List<T> rows) {
    ReactiveQuery<T> query = mock(ReactiveQuery.class, RETURNS_SELF); when(store.createQuery()).thenReturn(query); when(query.fetch()).thenReturn(Flux.fromIterable(rows));
  }
}
