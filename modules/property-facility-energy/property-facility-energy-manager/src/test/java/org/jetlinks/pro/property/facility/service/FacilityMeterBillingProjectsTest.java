package org.jetlinks.pro.property.facility.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.hswebframework.web.api.crud.entity.*;
import org.hswebframework.web.authorization.exception.AccessDenyException;
import org.jetlinks.pro.property.facility.asset.FacilityAssetType;
import org.jetlinks.pro.property.facility.entity.MeterBillEntity;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class FacilityMeterBillingProjectsTest {
  @Test void multiProjectQueryKeepsDeviceAssetIntersectionAndPaging() {
    var scope = mock(FacilityScope.class); var bills = mock(MeterBillStore.class);
    var service = new FacilityMeterBilling(scope, null, bills, null, null);
    when(scope.query(any(), eq(FacilityAssetType.facilityDevice), eq("deviceId"))).thenAnswer(call -> {
      QueryParamEntity query = call.getArgument(0);
      assertTrue(query.isPaging()); assertEquals(200,query.getPageSize());
      assertTrue(contains(query.getTerms(), "projectId", List.of("one","two")));
      assertTrue(contains(query.getTerms(), "state", "pending"));
      return Mono.just(query.toNestQuery().in("deviceId",List.of("authorized-device")).getParam());
    });
    when(bills.queryPager(any(QueryParamEntity.class))).thenAnswer(call -> {
      QueryParamEntity query = call.getArgument(0);
      assertTrue(contains(query.getTerms(), "deviceId", List.of("authorized-device")));
      return Mono.just(PagerResult.of(0,List.<MeterBillEntity>of(),query));
    });
    var query = new QueryParamEntity(); query.setPageSize(999); query.setPaging(false);
    StepVerifier.create(service.reviewsProjects(List.of("one","two"),"pending",query)).expectNextCount(1).verifyComplete();
    assertEquals(999,query.getPageSize());
    verify(scope).query(any(),eq(FacilityAssetType.facilityDevice),eq("deviceId"));
  }

  private static boolean contains(List<org.hswebframework.ezorm.core.param.Term> terms, String column, Object value) {
    return terms.stream().anyMatch(term -> column.equals(term.getColumn()) && Objects.equals(value,term.getValue()) ||
      term.getTerms() != null && contains(term.getTerms(),column,value));
  }

  @Test void deniedAssetScopeNeverQueriesBills() {
    var scope = mock(FacilityScope.class); var bills = mock(MeterBillStore.class);
    when(scope.query(any(),any(),anyString())).thenReturn(Mono.error(new AccessDenyException("denied")));
    var service = new FacilityMeterBilling(scope,null,bills,null,null);
    StepVerifier.create(service.reviewsProjects(List.of(),"pending",new QueryParamEntity())).expectError(AccessDenyException.class).verify();
    verifyNoInteractions(bills);
  }
}
