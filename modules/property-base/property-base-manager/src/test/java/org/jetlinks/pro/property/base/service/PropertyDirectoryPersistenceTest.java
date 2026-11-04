package org.jetlinks.pro.property.base.service;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.authorization.Authentication;
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
    @Autowired DatabaseClient database;
    @Autowired PropertyDirectoryCommandSupport directory;
    private final Map<String, Asset> cloudAssets = new HashMap<>();

    @BeforeEach
    void prepare() {
        organizations.deleteById(organizations.createQuery().fetch().map(PropertyOrganizationEntity::getId))
            .block(OrganizationPersistenceTest.TIMEOUT);
        cloudAssets.clear();
        reset(assets);
        when(assets.getAssets(any(AssetType.class), anyCollection())).thenAnswer(call -> {
            assertEquals("organization", call.<AssetType>getArgument(0).getId());
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

    private Flux<PropertyOrganizationInfo> command() {
        return CommandSupportManagerProviders.getCommandSupport(QueryPropertyProjectsCommand.SERVICE_ID)
            .flatMapMany(support -> support.execute(new QueryPropertyProjectsCommand()));
    }

    private Context access() {
        var user = new SimpleUser(); user.setId("reader");
        var auth = new SimpleAuthentication(); auth.setUser(user);
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

    @Import({OrganizationPersistenceTest.Config.class, PropertyDirectoryCommandSupport.class})
    static class Config {
        @Bean AssetManager assets() { return mock(AssetManager.class); }
        @Bean ArchiveScope archive(ReactiveRepository<PropertyOrganizationEntity, String> organizations,
            ReactiveRepository<PropertySpaceBindingEntity, String> bindings,
            ReactiveRepository<PropertySpaceEntity, String> spaces) {
            return new ArchiveScope(organizations, bindings, spaces);
        }
    }
}
