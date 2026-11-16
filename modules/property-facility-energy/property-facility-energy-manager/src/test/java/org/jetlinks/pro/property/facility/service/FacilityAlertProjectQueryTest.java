package org.jetlinks.pro.property.facility.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.hswebframework.web.api.crud.entity.*;
import org.jetlinks.pro.property.facility.asset.FacilityAssetType;
import org.jetlinks.pro.property.facility.entity.*;
import org.jetlinks.pro.property.base.organization.PropertyOrganizationInfo;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.*;

class FacilityAlertProjectQueryTest {
  @Test void listIntersectsProjectDirectoryBeforePaginationAndKeepsAssetFiltering() {
    var scope = mock(FacilityScope.class); var directory = mock(FacilityDirectory.class);
    var store = mock(AlertStore.class); var project = new PropertyOrganizationInfo(); project.setId("current");
    when(directory.projects()).thenReturn(Flux.just(project));
    when(scope.query(any(), eq(FacilityAssetType.facilityDevice), eq("deviceId")))
      .thenAnswer(call -> Mono.just(call.getArgument(0)));
    when(store.queryPager(any(QueryParamEntity.class))).thenReturn(Mono.just(PagerResult.of(0, List.<AlertEntity>of(), new QueryParamEntity())));
    var service = new FacilityQueries(scope, null, directory);
    service.childrenInProjects(store, new QueryParamEntity(), FacilityAssetType.facilityDevice, "deviceId").block();
    var captured = org.mockito.ArgumentCaptor.forClass(QueryParamEntity.class);
    verify(scope).query(captured.capture(), eq(FacilityAssetType.facilityDevice), eq("deviceId"));
    assertTrue(hasProject(captured.getValue().getTerms()));
    when(directory.projects()).thenReturn(Flux.empty()); clearInvocations(store, scope);
    assertEquals(0, service.childrenInProjects(store, new QueryParamEntity(), FacilityAssetType.facilityDevice, "deviceId").block().getTotal());
    verifyNoInteractions(store, scope);
  }
  private static boolean hasProject(List<org.hswebframework.ezorm.core.param.Term> terms) {
    return terms.stream().anyMatch(term -> "projectId".equals(term.getColumn()) && term.getValue().equals(List.of("current"))
      || term.getTerms() != null && hasProject(term.getTerms()));
  }
}
