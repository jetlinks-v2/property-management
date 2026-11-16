package org.jetlinks.pro.patrol.service;

import org.hswebframework.web.authorization.simple.*;
import org.jetlinks.pro.assets.*;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.assets.impl.*;
import org.jetlinks.pro.assets.impl.entity.DimensionAssetBindingEntity;
import org.jetlinks.pro.assets.scope.*;
import org.jetlinks.pro.authorize.OrgDimensionType;
import org.jetlinks.pro.patrol.asset.*;
import org.jetlinks.pro.patrol.entity.PatrolTagEntity;
import org.jetlinks.pro.patrol.integration.ProjectTestContexts;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.*;
import reactor.test.StepVerifier;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 使用真实绑定表、应用 Scope 和组织 Holder，验证项目归属不会扩大同级项目权限。 */
class PatrolProjectAssetBindingTest {
    @Test
    void automaticBindingKeepsApplicationAndRegistersOnlyPersistedProject() {
        try (var db = new PatrolDatabase()) {
            var repository = db.repository(DimensionAssetBindingEntity.class, DimensionAssetBindingEntity::new);
            var manager = new DimensionAssetBindManager(repository);
            var tags = new PatrolTagService(db.repository(PatrolTagEntity.class, PatrolTagEntity::new));
            var supplier = mock(PatrolAssetSupplier.class);
            doReturn(tags).when(supplier).service(PatrolAssetType.patrolTag);
            var context = ProjectTestContexts.project("app");
            var materializers = AssetsHolderHolder.registerScopeMaterializers(new AssetScopeMaterializers(List.of(),
                    new DimensionAssetScopeMaterializer(mock(AssetManager.class), manager)));
            try {
                var auth = new SimpleAuthentication();
                var user = new SimpleUser(); user.setId("alice"); auth.setUser(user);
                var activation = AssetScopeActivation.activate(auth,
                        SimpleDimension.of("app", "Patrol", SimpleDimensionType.of("business_application"), Map.of()),
                        Map.of("*", AssetScopeStrategy.SELECT_AND_BIND));
                var identity = activation.write().apply(reactor.util.context.Context.empty());
                var scoped = AssetsHolder.currentReactive().contextWrite(identity).block();
                var holder = new PatrolProjectAssetsHolder(scoped, context, supplier, manager);
                for (var id : List.of("one", "two")) {
                    var row = new PatrolTagEntity(); row.setId(id); row.setName(id);
                    row.setScopeKey("app"); row.setProjectId("project"); tags.insert(row).block();
                }
                holder.bindAssets(PatrolAssetType.patrolTag, List.of("one", "two"))
                        .contextWrite(identity).block();
                assertEquals(4, repository.createQuery().count().block());
                var own = organization(manager, "project");
                assertEquals(Set.of("one", "two"), new HashSet<>(own.filter("patrolTag", List.of("one", "two"),
                        false, CrudAssetPermission.read).collectList().block()));
                assertTrue(organization(manager, "other-project").filter("patrolTag", List.of("one", "two"),
                        false, CrudAssetPermission.read).collectList().block().isEmpty());
                // 重复登记不能把已有的只读组织授权覆盖成全权限。
                var key = DimensionAssetBindingEntity.generateId("org", "project", "patrolTag", "one");
                repository.createUpdate().set("permission", CrudAssetPermission.read.getMask()).where("id", key).execute().block();
                holder.bindAssets("patrolTag", List.of("one")).contextWrite(identity).block();
                assertEquals(CrudAssetPermission.read.getMask(), repository.findById(key).block().getPermission());
                assertEquals(4, repository.createQuery().count().block());
                var foreign = new PatrolTagEntity(); foreign.setId("foreign"); foreign.setName("foreign");
                foreign.setScopeKey("other-app"); foreign.setProjectId("project"); tags.insert(foreign).block();
                StepVerifier.create(holder.bindAssets("patrolTag", List.of("two", "foreign")).contextWrite(identity))
                        .expectError(org.hswebframework.web.authorization.exception.AccessDenyException.class).verify();
                var outside = new PatrolTagEntity(); outside.setId("outside"); outside.setName("outside");
                outside.setScopeKey("app"); outside.setProjectId("other-project"); tags.insert(outside).block();
                StepVerifier.create(holder.bindAssets("patrolTag", List.of("outside")).contextWrite(identity))
                        .expectError(org.hswebframework.web.authorization.exception.AccessDenyException.class).verify();
                assertEquals(4, repository.createQuery().count().block());
            } finally { materializers.dispose(); }
        }
    }

    @Test
    void organizationDescendantQueryUsesAuthorizedSnapshotWithoutLocalClosure() {
        try (var db = new PatrolDatabase()) {
            db.repository(org.jetlinks.pro.assets.impl.entity.DimensionClosureEntity.class,
                    org.jetlinks.pro.assets.impl.entity.DimensionClosureEntity::new);
            var repository = db.repository(DimensionAssetBindingEntity.class, DimensionAssetBindingEntity::new);
            var manager = new DimensionAssetBindManager(repository);
            var tags = new PatrolTagService(db.repository(PatrolTagEntity.class, PatrolTagEntity::new));
            var supplier = mock(PatrolAssetSupplier.class); doReturn(tags).when(supplier).service(PatrolAssetType.patrolTag);
            for (var id : List.of("visible", "outside", "other-application")) {
                var row = new PatrolTagEntity(); row.setId(id); row.setName(id);
                row.setScopeKey(id.equals("other-application") ? "foreign" : "app");
                row.setProjectId(id.equals("outside") ? "outside-project" : "project"); tags.insert(row).block();
                var binding = new AssetBindRequest(); binding.setTargetType("org");
                binding.setTargetId(row.getProjectId()); binding.setAssetType("patrolTag");
                binding.setAssetIdList(List.of(id)); binding.setRelation(AssetRelation.owner); binding.setAllPermission(true);
                manager.bindAssets(Flux.just(binding)).block();
            }
            var auth = new SimpleAuthentication(); var user = new SimpleUser(); user.setId("alice"); auth.setUser(user);
            var dimensions = new ArrayList<org.hswebframework.web.authorization.Dimension>();
            dimensions.add(SimpleDimension.of("company", "Company", OrgDimensionType.org,
                    Map.of("bindAssets", true, "direct", true)));
            dimensions.add(SimpleDimension.of("project", "Project", OrgDimensionType.org, Map.of("bindAssets", false)));
            for (int i=0; i<5; i++) dimensions.add(SimpleDimension.of("role-"+i, "Role", SimpleDimensionType.of("role"), Map.of()));
            auth.setDimensions(dimensions);
            var support = new org.jetlinks.pro.assets.impl.supports.OrgIncludeChildrenAssetsAccessSupport(mock(AssetManager.class));
            var original = mock(AssetsHolder.class);
            when(original.injectQuery(any(org.hswebframework.web.api.crud.entity.QueryParamEntity.class),
                    eq("patrolTag"), eq("id"), any(AssetPermission[].class))).thenAnswer(call -> {
                org.hswebframework.web.api.crud.entity.QueryParamEntity query = call.getArgument(0);
                query.toNestQuery(nest -> support.injectConditional0(nest.nest(), auth, "patrolTag", "id", CrudAssetPermission.read).block());
                return Mono.just(query);
            });
            var originalQuery = original.injectQuery(new org.hswebframework.web.api.crud.entity.QueryParamEntity(),
                    "patrolTag", "id", CrudAssetPermission.read).block();
            assertEquals(0, tags.count(originalQuery).block());
            var holder = new PatrolProjectAssetsHolder(original, ProjectTestContexts.project("app"), supplier, manager);
            var query = holder.injectQuery(new org.hswebframework.web.api.crud.entity.QueryParamEntity(),
                    "patrolTag", "id", CrudAssetPermission.read).block();
            assertEquals(List.of("visible"), tags.query(query).map(PatrolTagEntity::getId).collectList().block());
        }
    }

    private AssetsHolder organization(AssetBindManager manager, String project) {
        return new DynamicCustomDimensionAssetsHolder(mock(AssetManager.class), manager, "patrolTag",
                ignored -> Flux.just(SimpleDimension.of(project, project, OrgDimensionType.org, Map.of())));
    }
}
