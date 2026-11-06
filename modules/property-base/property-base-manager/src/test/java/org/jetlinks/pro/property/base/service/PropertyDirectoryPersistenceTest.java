package org.jetlinks.pro.property.base.service;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.authorization.exception.AccessDenyException;
import org.hswebframework.web.authorization.simple.SimpleAuthentication;
import org.hswebframework.web.authorization.simple.SimpleUser;
import org.jetlinks.pro.assets.*;
import org.jetlinks.pro.assets.impl.CreatorAssetsHolder;
import org.jetlinks.pro.command.CommandSupportManagerProviders;
import org.jetlinks.pro.property.base.command.*;
import org.jetlinks.pro.property.base.entity.*;
import org.jetlinks.pro.property.base.organization.OrganizationType;
import org.jetlinks.pro.property.base.organization.PropertyOrganizationInfo;
import org.jetlinks.pro.tenant.holder.TenantHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 真实 PostgreSQL 与命令注册，真实创建者/租户 Holder；只替换外部 Cloud 资产读取。 */
@SpringBootTest(classes = PropertyDirectoryPersistenceTest.Config.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
class PropertyDirectoryPersistenceTest {
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        OrganizationPersistenceTest.database(registry);
    }

    @Autowired ReactiveRepository<PropertyOrganizationEntity, String> organizations;
    @Autowired AssetManager assets;
    @Autowired ReactiveRepository<PropertyUserEntity, String> users;
    @Autowired PropertyUserService userService;
    @Autowired DatabaseClient database;
    @Autowired PropertyDirectoryCommandSupport directory;
    private final Map<String, Asset> cloudAssets = new HashMap<>();

    @BeforeEach
    void prepare() {
        organizations.deleteById(organizations.createQuery().fetch().map(PropertyOrganizationEntity::getId))
            .block(OrganizationPersistenceTest.TIMEOUT);
        users.deleteById(users.createQuery().fetch().map(PropertyUserEntity::getId))
            .block(OrganizationPersistenceTest.TIMEOUT);
        cloudAssets.clear();
        reset(assets);
        when(assets.getAssets(any(AssetType.class), anyCollection())).thenAnswer(call -> {
            assertTrue(Set.of("organization", "user").contains(call.<AssetType>getArgument(0).getId()));
            Collection<?> ids = call.getArgument(1);
            return Flux.fromIterable(ids).map(Object::toString).filter(cloudAssets::containsKey).map(cloudAssets::get);
        });
    }

    @Test
    void commandReadsOwnMirrorAndIntersectsAssetAndTenantWithoutCloudTable() {
        add("allowed", "reader", "tenant-a", OrganizationType.project, false);
        add("foreign-tenant", "reader", "tenant-b", OrganizationType.project, false);
        add("forbidden", "someone-else", "tenant-a", OrganizationType.project, false);
        add("deleted", "reader", "tenant-a", OrganizationType.project, true);
        add("external", "reader", "tenant-a", OrganizationType.external, false);
        var count = database.sql("select count(*) as n from information_schema.tables "
            + "where table_schema='public' and table_name='s_organization'")
            .map((row, meta) -> row.get("n", Long.class)).one().block();
        assertEquals(0L, count);

        StepVerifier.create(command().contextWrite(access()).contextWrite(TenantHolder.makeCurrent("tenant-a")))
            .assertNext(project -> assertEquals("allowed", project.getId())).verifyComplete();
        // 归属在下一次命令即时生效，不在报修中维护第二套项目镜像或缓存授权列表。
        cloudAssets.put("allowed", asset("allowed", "reader", "tenant-b"));
        StepVerifier.create(command().contextWrite(access()).contextWrite(TenantHolder.makeCurrent("tenant-a")))
            .verifyComplete();
    }

    @Test
    void emptyAndDeniedProjectsStayEmptyAndMissingContextIsRejected() {
        StepVerifier.create(command().contextWrite(access()).contextWrite(TenantHolder.makeCurrent("tenant-a")))
            .verifyComplete();
        add("forbidden", "someone-else", "tenant-a", OrganizationType.project, false);
        var request = new QueryPropertySpacesCommand(); request.setProjectId("forbidden");
        StepVerifier.create(directory.spaces(request).contextWrite(access())
            .contextWrite(TenantHolder.makeCurrent("tenant-a"))).expectError(AccessDenyException.class).verify();
        StepVerifier.create(directory.projects(new QueryPropertyProjectsCommand()).contextWrite(access()))
            .expectError(AccessDenyException.class).verify();
    }

    @Test
    void userMirrorFiltersRealAssetsBeforePagingCountsAndCommandWithoutCloudUserTable() {
        addUser("a-forbidden", "someone-else", "tenant-a");
        addUser("b-allowed", "reader", "tenant-a");
        addUser("c-allowed", "reader", "tenant-a");
        addUser("d-foreign", "reader", "tenant-b");
        var absent = database.sql("select count(*) as n from information_schema.tables "
            + "where table_schema='public' and table_name='s_user'")
            .map((row, meta) -> row.get("n", Long.class)).one().block();
        assertEquals(0L, absent);
        var query = new QueryParamEntity(); query.setPageSize(1); query.setPageIndex(1);
        query.orderBy("id");
        var page = userService.query(query).contextWrite(access()).block(OrganizationPersistenceTest.TIMEOUT);
        assertEquals(3, page.getTotal());
        assertEquals("c-allowed", page.getData().get(0).getId());
        assertEquals(3, userService.counts(new QueryParamEntity()).contextWrite(access())
            .block(OrganizationPersistenceTest.TIMEOUT).all());
        var command = new QueryPropertyUsersCommand();
        StepVerifier.create(CommandSupportManagerProviders.getCommandSupport(QueryPropertyUsersCommand.SERVICE_ID)
            .flatMapMany(support -> support.execute(command)).map(user -> user.getId()).collectList()
            .contextWrite(access()).contextWrite(TenantHolder.makeCurrent("tenant-a")))
            .assertNext(ids -> assertEquals(Set.of("b-allowed", "c-allowed"), Set.copyOf(ids)))
            .verifyComplete();
        var denied = QueryParamEntity.newQuery().where("id", "a-forbidden").getParam();
        assertEquals(0, userService.query(denied).contextWrite(access())
            .block(OrganizationPersistenceTest.TIMEOUT).getTotal());
        when(assets.getAssets(any(AssetType.class), anyCollection()))
            .thenReturn(Flux.error(new IllegalStateException("asset service unavailable")));
        StepVerifier.create(userService.query(new QueryParamEntity()).contextWrite(access()))
            .expectErrorMessage("asset service unavailable").verify();
    }

    private void addUser(String id, String creator, String tenant) {
        var row = new PropertyUserEntity(); row.setId(id); row.setName(id); row.setUsername(id);
        row.setStatus((byte) 1); row.setAccountType("user"); row.setOwner(false); row.setEmployee(true);
        row.setVersion(0L); row.setDeleted(false);
        users.save(row).block(OrganizationPersistenceTest.TIMEOUT);
        cloudAssets.put(id, new DefaultAsset(id, id, AssetType.of("user"))
            .withOption("creatorId", creator).withOption("tenantId", tenant));
    }

    private Flux<PropertyOrganizationInfo> command() {
        return CommandSupportManagerProviders.getCommandSupport(QueryPropertyProjectsCommand.SERVICE_ID)
            .flatMapMany(support -> support.execute(new QueryPropertyProjectsCommand()));
    }

    private Context access() {
        var user = new SimpleUser(); user.setId("reader");
        var auth = new SimpleAuthentication(); auth.setUser(user);
        // 应用账号可能有七个非组织维度；原 SQL 注入会构造空组织目标，批量资产校验不依赖该分支。
        auth.setDimensions(java.util.stream.IntStream.range(0, 7)
            .mapToObj(i -> (org.hswebframework.web.authorization.Dimension)
                org.hswebframework.web.authorization.simple.SimpleDimension.of("app-" + i, "app",
                    new org.hswebframework.web.authorization.DimensionType() {
                        public String getId() { return "business_application"; }
                        public String getName() { return "application"; }
                    }, Map.of())).toList());
        return Context.of(Authentication.class, auth, AssetsHolder.class, new CreatorAssetsHolder(user, assets));
    }

    private void add(String id, String creator, String tenant, OrganizationType type, boolean deleted) {
        var row = new PropertyOrganizationEntity(); row.setId(id); row.setName(id);
        row.setOrganizationType(type); row.setVersion(0L); row.setDeleted(deleted);
        organizations.save(row).block(OrganizationPersistenceTest.TIMEOUT);
        cloudAssets.put(id, asset(id, creator, tenant));
    }

    private Asset asset(String id, String creator, String tenant) {
        return new DefaultAsset(id, id, ArchiveScope.ORGANIZATION)
            .withOption("creatorId", creator).withOption("tenantId", tenant);
    }

    @Import({OrganizationPersistenceTest.Config.class, PropertyDirectoryCommandSupport.class, PropertyUserScope.class, PropertyUserService.class})
    static class Config {
        @Bean SaasUserSource userSource() {
            return new SaasUserSource() {
                @Override public Flux<String> authorizedIds(List<String> ids) {
                    return AssetsHolder.filterAssets(Flux.fromIterable(ids), AssetType.of("user"),
                        id -> id, org.jetlinks.pro.assets.crud.CrudAssetPermission.read);
                }
            };
        }
        @Bean AssetManager assets() { return mock(AssetManager.class); }
        @Bean ArchiveScope archive(ReactiveRepository<PropertyOrganizationEntity, String> organizations,
            ReactiveRepository<PropertySpaceBindingEntity, String> bindings,
            ReactiveRepository<PropertySpaceEntity, String> spaces) {
            return new ArchiveScope(organizations, bindings, spaces);
        }
    }
}
