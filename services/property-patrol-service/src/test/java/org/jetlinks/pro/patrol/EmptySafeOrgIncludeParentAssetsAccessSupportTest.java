package org.jetlinks.pro.patrol;

import org.hswebframework.ezorm.core.param.Term;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.authorization.Dimension;
import org.hswebframework.web.authorization.simple.SimpleAuthentication;
import org.hswebframework.web.authorization.simple.SimpleDimensionType;
import org.jetlinks.pro.assets.AssetManager;
import org.jetlinks.pro.assets.AssetSupplier;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.assets.impl.DefaultAssetManager;
import org.jetlinks.pro.assets.impl.supports.OrgIncludeParentAssetsAccessSupport;
import org.jetlinks.pro.assets.impl.terms.DimensionAssetsTerm;
import org.jetlinks.pro.assets.impl.terms.DimensionAssetsTermBuilder;
import org.jetlinks.pro.authorize.OrgDimensionType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EmptySafeOrgIncludeParentAssetsAccessSupportTest {

    private final AssetManager assetManager = new DefaultAssetManager(
        new DefaultListableBeanFactory().getBeanProvider(AssetSupplier.class));

    private final OrgIncludeParentAssetsAccessSupport support =
        new EmptySafeOrgIncludeParentAssetsAccessSupport(assetManager);

    @Test
    void emptyOrganizationShouldDenyItsBranchRegardlessOfOtherDimensions() {
        for (int count : List.of(0, 4, 6, 7, 12)) {
            QueryParamEntity query = inject(support, authentication(count, false), CrudAssetPermission.read);
            List<Term> terms = leaves(query.getTerms());
            assertEquals(List.of("isnull", "notnull"), terms.stream().map(Term::getTermType).toList());
            assertTrue(terms.stream().allMatch(term -> "id".equals(term.getColumn())));
        }
    }

    @Test
    void originalOptimizationReproducesEmptyTargetWithSevenUnrelatedDimensions() {
        QueryParamEntity query = inject(new OrgIncludeParentAssetsAccessSupport(assetManager),
                                       authentication(7, false), CrudAssetPermission.read);
        Term term = leaves(query.getTerms()).stream()
            .filter(value -> DimensionAssetsTermBuilder.termType.equals(value.getTermType()))
            .findFirst().orElseThrow();
        assertThrows(org.hswebframework.web.exception.ValidationException.class,
                     () -> DimensionAssetsTerm.of(term));
    }

    @Test
    void existingOrganizationShouldKeepFrameworkQueryAndWriteConditions() {
        for (CrudAssetPermission permission : List.of(CrudAssetPermission.read, CrudAssetPermission.save)) {
            Authentication authentication = authentication(7, true);
            QueryParamEntity actual = inject(support, authentication, permission);
            QueryParamEntity expected = inject(new OrgIncludeParentAssetsAccessSupport(assetManager),
                                              authentication, permission);
            assertEquals(com.alibaba.fastjson.JSON.toJSONString(expected),
                         com.alibaba.fastjson.JSON.toJSONString(actual));
            for (Term term : leaves(actual.getTerms())) {
                if (DimensionAssetsTermBuilder.termType.equals(term.getTermType())) {
                    assertFalse(DimensionAssetsTerm.of(term).getTargets().isEmpty());
                }
            }
        }
    }

    @Test
    void configurationShouldReplaceOriginalBeanUnderItsExistingName() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(AssetManager.class, () -> assetManager);
            context.register(OriginalSupportScan.class, PatrolAssetsConfiguration.class);
            context.refresh();
            assertInstanceOf(EmptySafeOrgIncludeParentAssetsAccessSupport.class,
                             context.getBean("orgIncludeParentAssetsAccessSupport"));
            assertEquals(1, context.getBeansOfType(OrgIncludeParentAssetsAccessSupport.class).size());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ComponentScan(basePackages = "org.jetlinks.pro.assets.impl.supports", useDefaultFilters = false,
        includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
            classes = OrgIncludeParentAssetsAccessSupport.class))
    static class OriginalSupportScan {
    }

    private QueryParamEntity inject(OrgIncludeParentAssetsAccessSupport provider,
                                    Authentication authentication,
                                    CrudAssetPermission permission) {
        QueryParamEntity query = QueryParamEntity.of();
        query.toNestQuery(conditional -> provider
            .injectConditional0(conditional.nest(), authentication, "space", "id", permission).block());
        return query;
    }

    private Authentication authentication(int count, boolean organization) {
        SimpleAuthentication authentication = new SimpleAuthentication();
        List<Dimension> dimensions = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            dimensions.add(Dimension.of("app-" + index, "应用", SimpleDimensionType.of("business_application", "应用")));
        }
        if (organization) {
            dimensions.add(Dimension.of("org-1", "组织", OrgDimensionType.org));
        }
        authentication.setDimensions(dimensions);
        return authentication;
    }

    private List<Term> leaves(List<Term> terms) {
        List<Term> result = new ArrayList<>();
        for (Term term : terms) {
            if (term.getTerms().isEmpty()) {
                result.add(term);
            } else {
                result.addAll(leaves(term.getTerms()));
            }
        }
        return result;
    }
}
