package org.jetlinks.pro.property;

import com.alibaba.fastjson.JSON;
import org.jetlinks.pro.property.configuration.PropertyOrganizationAssetsConfiguration;
import org.hswebframework.ezorm.core.param.Term;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.authorization.Dimension;
import org.hswebframework.web.authorization.simple.SimpleAuthentication;
import org.jetlinks.pro.assets.AssetManager;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.assets.impl.supports.OrgIncludeChildrenAssetsAccessSupport;
import org.jetlinks.pro.assets.impl.supports.OrgIncludeParentAssetsAccessSupport;
import org.jetlinks.pro.authorize.OrgDimensionType;
import org.jetlinks.pro.property.quality.asset.QualityOrganizationAssetsConfiguration;
import org.jetlinks.pro.smartpark.repair.asset.RepairAssetsConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** 单进程组织权限不能重复注册、丢失专用规则，或因空范围放开任一业务查询。 */
class PropertyOrganizationAssetsConfigurationTest {
    private final AssetManager manager = mock(AssetManager.class);

    @Test
    void onlyOneProviderPerSupportIsRegistered() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.setAllowBeanDefinitionOverriding(false);
            context.registerBean(AssetManager.class, () -> manager);
            context.register(PropertyOrganizationAssetsConfiguration.class);
            context.refresh();
            assertEquals(1, context.getBeansOfType(OrgIncludeParentAssetsAccessSupport.class).size());
            assertEquals(1, context.getBeansOfType(OrgIncludeChildrenAssetsAccessSupport.class).size());
        }
    }

    @Test
    void expandedScopeMatchesEachOriginalDomainRuleForReadAndWrite() {
        var unified = new PropertyOrganizationAssetsConfiguration();
        var quality = new QualityOrganizationAssetsConfiguration();
        var repair = new RepairAssetsConfiguration();
        for (var type : List.of("qualityWorkOrder", "qualityTask", "repairOrder", "space", "facilityDevice")) {
            for (var permission : List.of(CrudAssetPermission.read, CrudAssetPermission.save)) {
                for (boolean organization : List.of(false, true)) {
                    var auth = new SimpleAuthentication();
                    auth.setDimensions(organization
                            ? List.of(Dimension.of("project-a", "项目A", OrgDimensionType.org)) : List.of());
                    var actual = QueryParamEntity.of();
                    var expected = QueryParamEntity.of();
                    var parent = unified.orgIncludeParentAssetsAccessSupport(manager);
                    var originalParent = type.startsWith("quality")
                            ? quality.orgIncludeParentAssetsAccessSupport(manager)
                            : repair.orgIncludeParentAssetsAccessSupport(manager);
                    actual.toNestQuery(n -> parent.injectConditional0(n.nest(), auth, type, "id", permission).block());
                    expected.toNestQuery(n -> originalParent.injectConditional0(n.nest(), auth, type, "id", permission).block());
                    assertEquals(JSON.toJSONString(expected), JSON.toJSONString(actual), type + permission);

                    actual = QueryParamEntity.of();
                    expected = QueryParamEntity.of();
                    var children = unified.orgIncludeChildrenAssetsAccessSupport(manager);
                    var originalChildren = type.startsWith("quality")
                            ? quality.orgIncludeChildrenAssetsAccessSupport(manager)
                            : repair.orgIncludeChildrenAssetsAccessSupport(manager);
                    actual.toNestQuery(n -> children.injectConditional0(n.nest(), auth, type, "id", permission).block());
                    expected.toNestQuery(n -> originalChildren.injectConditional0(n.nest(), auth, type, "id", permission).block());
                    assertEquals(JSON.toJSONString(expected), JSON.toJSONString(actual), type + permission);
                    if (!organization) {
                        assertEquals(List.of("isnull", "notnull"), leaves(actual.getTerms()).stream()
                                .map(Term::getTermType).toList(), type);
                    }
                }
            }
        }
    }

    private List<Term> leaves(List<Term> terms) {
        return terms.stream().flatMap(term -> term.getTerms().isEmpty()
                ? java.util.stream.Stream.of(term) : leaves(term.getTerms()).stream()).toList();
    }
}
