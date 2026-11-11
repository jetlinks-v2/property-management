package org.jetlinks.pro.smartpark.repair.asset;

import org.hswebframework.ezorm.core.param.Term;
import org.hswebframework.ezorm.rdb.metadata.RDBColumnMetadata;
import org.hswebframework.ezorm.rdb.metadata.RDBDatabaseMetadata;
import org.hswebframework.ezorm.rdb.metadata.RDBSchemaMetadata;
import org.hswebframework.ezorm.rdb.metadata.RDBTableMetadata;
import org.hswebframework.ezorm.rdb.metadata.dialect.Dialect;
import org.hswebframework.ezorm.rdb.operator.builder.fragments.SqlFragments;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.authorization.Dimension;
import org.hswebframework.web.authorization.simple.SimpleAuthentication;
import org.hswebframework.web.authorization.simple.SimpleDimension;
import org.hswebframework.web.authorization.simple.SimpleDimensionType;
import org.jetlinks.pro.assets.AssetManager;
import org.jetlinks.pro.assets.AssetSupplier;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.assets.impl.DefaultAssetManager;
import org.jetlinks.pro.assets.impl.direct.SimpleDirectAssetsTermFragmentBuilder;
import org.jetlinks.pro.assets.impl.supports.OrgAssetsAccessSupport;
import org.jetlinks.pro.assets.impl.supports.OrgIncludeChildrenAssetsAccessSupport;
import org.jetlinks.pro.assets.impl.supports.OrgIncludeParentAssetsAccessSupport;
import org.jetlinks.pro.assets.impl.terms.DimensionAssetsTermBuilder;
import org.jetlinks.pro.authorize.OrgDimensionType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;

import java.sql.DriverManager;
import java.sql.JDBCType;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 真实权限提供者生成的直接资产 SQL，在没有组织闭包数据的独立业务库中仍正确允拒。 */
class RepairOrganizationQueryTest {
    private final AssetManager assets = new DefaultAssetManager(
        new DefaultListableBeanFactory().getBeanProvider(AssetSupplier.class));

    @Test
    void childrenPermissionKeepsTheSameRowsWhenOrganizationCountCrossesTheOptimizationThreshold() throws Exception {
        try (var context = context()) {
            var support = context.getBean(OrgIncludeChildrenAssetsAccessSupport.class);
            for (int count : List.of(1, 6, 7, 1041)) {
                assertEquals(Set.of("own", "descendant"), rows(inject(support, authentication(count), CrudAssetPermission.read)));
            }
        }
    }

    @Test
    void originalHierarchyOptimizationReproducesTheEmptyList() throws Exception {
        assertEquals(Set.of(), rows(inject(new OrgIncludeChildrenAssetsAccessSupport(assets),
            authentication(1041), CrudAssetPermission.read)));
    }

    @Test
    void ordersAndConfigurationsUseTheSameAuthorizedOrganizationsForReadAndSave() throws Exception {
        try (var context = context()) {
            var support = context.getBean(OrgIncludeChildrenAssetsAccessSupport.class);
            for (var type : List.of("repairOrder", "repairConfiguration")) {
                for (var permission : List.of(CrudAssetPermission.read, CrudAssetPermission.save)) {
                    var query = inject(support, authentication(1041), type, permission);
                    assertEquals(Set.of("own", "descendant"), rows(query, type));
                }
            }
        }
    }

    @Test
    void parentOrganizationsRemainReadOnly() throws Exception {
        try (var context = context()) {
            var support = context.getBean(OrgIncludeParentAssetsAccessSupport.class);
            var authentication = authentication(1041);
            assertEquals(Set.of("own", "descendant", "parent"), rows(inject(support, authentication, CrudAssetPermission.read)));
            assertEquals(Set.of("own", "descendant"), rows(inject(support, authentication, CrudAssetPermission.save)));
        }
    }

    @Test
    void unrelatedApplicationDimensionsDoNotTurnAnEmptyOrganizationRangeIntoAllRows() {
        var authentication = new SimpleAuthentication();
        authentication.setDimensions(List.of(Dimension.of("app", "应用", SimpleDimensionType.of("business_application", "应用"))));
        try (var context = context()) {
            var query = inject(context.getBean(OrgIncludeChildrenAssetsAccessSupport.class), authentication, CrudAssetPermission.read);
            assertEquals(List.of("isnull", "notnull"), leaves(query.getTerms()).stream().map(Term::getTermType).toList());
        }
    }

    private AnnotationConfigApplicationContext context() {
        var context = new AnnotationConfigApplicationContext();
        context.registerBean(AssetManager.class, () -> assets);
        context.register(OriginalSupports.class, RepairAssetsConfiguration.class);
        context.refresh();
        assertEquals(1, context.getBeansOfType(OrgIncludeChildrenAssetsAccessSupport.class).size());
        assertEquals(1, context.getBeansOfType(OrgIncludeParentAssetsAccessSupport.class).size());
        return context;
    }

    @Configuration(proxyBeanMethods = false)
    @ComponentScan(basePackages = "org.jetlinks.pro.assets.impl.supports", useDefaultFilters = false,
        includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
            classes = {OrgIncludeChildrenAssetsAccessSupport.class, OrgIncludeParentAssetsAccessSupport.class}))
    static class OriginalSupports {
    }

    private QueryParamEntity inject(OrgAssetsAccessSupport support, Authentication authentication, CrudAssetPermission permission) {
        return inject(support, authentication, "repairOrder", permission);
    }

    private QueryParamEntity inject(OrgAssetsAccessSupport support, Authentication authentication,
                                    String assetType, CrudAssetPermission permission) {
        var query = new QueryParamEntity();
        query.toNestQuery(q -> support.injectConditional0(q.nest(), authentication, assetType, "id", permission).block());
        return query;
    }

    private Authentication authentication(int organizationCount) {
        var authentication = new SimpleAuthentication();
        List<Dimension> dimensions = new ArrayList<>();
        dimensions.add(SimpleDimension.of("own-org", "本组织", OrgDimensionType.org, Map.of("direct", true)));
        dimensions.add(SimpleDimension.of("child-org", "下级", OrgDimensionType.org, Map.of("direct", false)));
        dimensions.add(SimpleDimension.of("parent-org", "上级", OrgDimensionType.parentOrg, Map.of("direct", false)));
        dimensions.add(SimpleDimension.of("ignored-org", "排除", OrgDimensionType.org, Map.of("ignore", true)));
        for (int i = 1; i < organizationCount; i++) {
            dimensions.add(SimpleDimension.of("other-child-" + i, "下级", OrgDimensionType.org, Map.of("direct", false)));
        }
        authentication.setDimensions(dimensions);
        return authentication;
    }

    private Set<String> rows(QueryParamEntity query) throws Exception {
        return rows(query, "repairOrder");
    }

    private Set<String> rows(QueryParamEntity query, String assetType) throws Exception {
        boolean configuration = "repairConfiguration".equals(assetType);
        String tableName = configuration ? "sp_repair_configuration" : "sp_repair_order";
        String organizationColumn = configuration ? "business_project_id" : "organization_id";
        String organizationProperty = configuration ? "businessProjectId" : "organizationId";
        Term term = leaves(query.getTerms()).stream()
            .filter(t -> DimensionAssetsTermBuilder.termType.equals(t.getTermType())).findFirst().orElseThrow();
        var database = new RDBDatabaseMetadata(Dialect.POSTGRES);
        var schema = new RDBSchemaMetadata("public");
        database.addSchema(schema);
        var table = new RDBTableMetadata(tableName);
        schema.addTable(table);
        column(table, "id", "id");
        column(table, organizationColumn, organizationProperty);
        var closure = new RDBTableMetadata("s_dimension_closure");
        schema.addTable(closure);
        for (var name : List.of("type", "ancestor_id", "descendant_id")) column(closure, name, name);
        var builder = new SimpleDirectAssetsTermFragmentBuilder(assetType, tableName, Map.of("org", organizationProperty));
        RDBColumnMetadata id = table.getColumnNow("id");
        SqlFragments fragments = builder.createFragments(id.getFullName(), id, term);

        try (var connection = DriverManager.getConnection("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE")) {
            try (var statement = connection.createStatement()) {
                statement.execute("create table " + tableName + "(id varchar primary key, " + organizationColumn + " varchar)");
                statement.execute("create table s_dimension_closure(type varchar, ancestor_id varchar, descendant_id varchar)");
                statement.execute("insert into " + tableName + " values ('own','own-org'),('descendant','child-org'),('parent','parent-org'),('ignored','ignored-org'),('forbidden','foreign-org')");
            }
            String sql = "select id from " + tableName + " where " + String.join(" ", fragments.getSql());
            try (var statement = connection.prepareStatement(sql)) {
                List<Object> parameters = fragments.getParameters();
                for (int i = 0; i < parameters.size(); i++) statement.setObject(i + 1, parameters.get(i));
                Set<String> rows = new HashSet<>();
                try (var result = statement.executeQuery()) {
                    while (result.next()) rows.add(result.getString(1));
                }
                return rows;
            }
        }
    }

    private void column(RDBTableMetadata table, String name, String alias) {
        var column = new RDBColumnMetadata();
        column.setName(name);
        column.setAlias(alias);
        column.setJdbcType(JDBCType.VARCHAR, String.class);
        table.addColumn(column);
    }

    private List<Term> leaves(List<Term> terms) {
        List<Term> result = new ArrayList<>();
        for (var term : terms) {
            if (term.getTerms().isEmpty()) result.add(term);
            else result.addAll(leaves(term.getTerms()));
        }
        return result;
    }
}
