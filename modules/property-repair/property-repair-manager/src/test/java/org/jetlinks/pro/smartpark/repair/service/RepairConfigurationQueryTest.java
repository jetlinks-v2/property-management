package org.jetlinks.pro.smartpark.repair.service;

import org.hswebframework.ezorm.core.param.Term;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.authorization.simple.SimpleUser;
import org.jetlinks.pro.assets.AssetsHolder;
import org.jetlinks.pro.assets.impl.CreatorAssetsHolder;
import org.jetlinks.pro.property.base.organization.PropertyOrganizationInfo;
import org.jetlinks.pro.property.service.TenantAssetScope;
import org.jetlinks.pro.smartpark.repair.web.RepairConfigurationController;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 配置自身权限保持真实 Holder 注入；组织范围只来自独立基础服务的授权目录。 */
class RepairConfigurationQueryTest {
    @Test
    void projectScopeIsIntersectedBeforePagingWithoutOrganizationSql() {
        var directory = mock(RepairDirectory.class);
        var project = new PropertyOrganizationInfo(); project.setId("allowed-project");
        when(directory.projects()).thenReturn(Flux.just(project));
        var input = QueryParamEntity.newQuery().where("businessProjectId", "forged-project")
            .or("applicationId", "forged-application").doPaging(2, 12).getParam();
        StepVerifier.create(controller(directory).injectQueryParam(input).contextWrite(context -> context.put(AssetsHolder.class, holder())))
            .assertNext(query -> {
                assertEquals(2, query.getPageIndex()); assertEquals(12, query.getPageSize());
                var terms = flatten(query.getTerms()).toList();
                assertTrue(terms.stream().anyMatch(t -> "businessProjectId".equals(t.getColumn())
                    && "in".equals(t.getTermType()) && List.of("allowed-project").equals(t.getValue())));
                assertTrue(terms.stream().anyMatch(t -> "applicationId".equals(t.getColumn())
                    && "application".equals(t.getValue())));
                assertTrue(terms.stream().anyMatch(t -> t.getTermType().contains("repairConfiguration")));
                assertFalse(terms.stream().anyMatch(t -> t.getTermType().contains("organization")));
                assertEquals(2, input.getTerms().size());
            }).verifyComplete();
        verify(directory).projects();
    }

    @Test
    void emptyAuthorizedDirectoryCannotReturnUnscopedConfigurations() {
        var directory = mock(RepairDirectory.class); when(directory.projects()).thenReturn(Flux.empty());
        StepVerifier.create(controller(directory).injectQueryParam(new QueryParamEntity())
            .contextWrite(context -> context.put(AssetsHolder.class, holder()))).assertNext(query -> {
                var terms = flatten(query.getTerms()).filter(t -> "businessProjectId".equals(t.getColumn())).toList();
                assertEquals(2, terms.size());
                assertTrue(terms.stream().anyMatch(t -> "isnull".equals(t.getTermType())));
                assertTrue(terms.stream().anyMatch(t -> "notnull".equals(t.getTermType())));
            }).verifyComplete();
    }

    @Test
    void directoryFailurePropagatesWithoutLocalOrUnscopedFallback() {
        var directory = mock(RepairDirectory.class);
        when(directory.projects()).thenReturn(Flux.error(new IllegalStateException("base service unavailable")));
        StepVerifier.create(controller(directory).injectQueryParam(new QueryParamEntity())
            .contextWrite(context -> context.put(AssetsHolder.class, holder()))).expectErrorMessage("base service unavailable").verify();
    }

    private RepairConfigurationController controller(RepairDirectory directory) {
        var contexts = mock(RepairContextProvider.class);
        when(contexts.current()).thenReturn(Mono.just(new RepairContext("tenant", "application", "reader", "员工")));
        var tenants = mock(TenantAssetScope.class);
        when(tenants.injectQuery(any(), any(), eq("id"))).thenAnswer(call -> Mono.just(call.getArgument(0)));
        return new RepairConfigurationController(contexts, tenants, null, null, directory);
    }

    private AssetsHolder holder() {
        var user = new SimpleUser(); user.setId("reader");
        return new CreatorAssetsHolder(user, null);
    }

    private Stream<Term> flatten(List<Term> terms) {
        return terms.stream().flatMap(term -> Stream.concat(Stream.of(term), flatten(term.getTerms())));
    }
}
