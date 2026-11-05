package org.jetlinks.pro.property.quality.asset;

import org.hswebframework.ezorm.core.param.Term;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.authorization.Dimension;
import org.hswebframework.web.authorization.simple.SimpleAuthentication;
import org.hswebframework.web.authorization.simple.SimpleDimensionType;
import org.jetlinks.pro.assets.AssetManager;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.assets.impl.supports.OrgIncludeChildrenAssetsAccessSupport;
import org.jetlinks.pro.assets.impl.terms.DimensionAssetsTerm;
import org.jetlinks.pro.authorize.OrgDimensionType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class QualityOrganizationAssetsConfigurationTest {
    private final OrgIncludeChildrenAssetsAccessSupport support =
        new QualityOrganizationAssetsConfiguration().orgIncludeChildrenAssetsAccessSupport(mock(AssetManager.class));

    @Test
    void manyUnrelatedRolesMustNotRequireLocalCloudClosureTable() {
        var auth = authentication(true);
        var query = QueryParamEntity.of();
        query.toNestQuery(nest -> support.injectConditional0(nest.nest(), auth, "qualityWorkOrder", "id",
                                                            CrudAssetPermission.read).block());
        Term term = leaves(query.getTerms()).stream().filter(t -> "dim-assets".equals(t.getTermType()))
            .findFirst().orElseThrow();
        var assets = DimensionAssetsTerm.of(term);
        assertNotEquals(Boolean.TRUE, assets.getClosure());
        assertEquals(List.of("project-a"), assets.getTargets().stream().map(d -> d.getId()).toList());
        assertFalse(assets.getTargets().stream().anyMatch(d -> "project-b".equals(d.getId())));
    }

    @Test
    void noOrganizationMustDenyOrganizationBranch() {
        var query = QueryParamEntity.of();
        query.toNestQuery(nest -> support.injectConditional0(nest.nest(), authentication(false), "qualityWorkOrder", "id",
                                                            CrudAssetPermission.read).block());
        assertEquals(List.of("isnull", "notnull"), leaves(query.getTerms()).stream().map(Term::getTermType).toList());
    }

    private static SimpleAuthentication authentication(boolean organization) {
        var auth = new SimpleAuthentication();
        List<Dimension> dimensions = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            dimensions.add(Dimension.of("role-" + i, "角色", SimpleDimensionType.of("role", "角色")));
        }
        if (organization) dimensions.add(Dimension.of("project-a", "项目A", OrgDimensionType.org));
        auth.setDimensions(dimensions);
        return auth;
    }

    private static List<Term> leaves(List<Term> terms) {
        return terms.stream().flatMap(t -> t.getTerms().isEmpty() ? java.util.stream.Stream.of(t)
            : leaves(t.getTerms()).stream()).toList();
    }
}
