package org.jetlinks.pro.smartpark.handover.service;

import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.authorization.exception.AccessDenyException;
import org.hswebframework.web.authorization.simple.SimpleAuthentication;
import org.hswebframework.web.authorization.simple.SimpleUser;
import org.hswebframework.web.crud.annotation.EnableEasyormRepository;
import org.hswebframework.web.crud.configuration.*;
import org.jetlinks.pro.assets.*;
import org.jetlinks.pro.property.base.organization.OrganizationType;
import org.jetlinks.pro.property.base.organization.PropertyOrganizationInfo;
import org.jetlinks.pro.property.base.user.PropertyEmployeeInfo;
import org.jetlinks.pro.property.base.user.PropertyUserInfo;
import org.jetlinks.pro.smartpark.handover.asset.HandoverProblemAccess;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.r2dbc.R2dbcAutoConfiguration;
import org.springframework.boot.autoconfigure.r2dbc.R2dbcTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.containers.GenericContainer;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/** 独立临时PostgreSQL；真实EasyORM/事务/CAS，仅外部目录和授权服务使用受约束替身。 */
public final class HandoverPersistenceFixture {
    public static final Duration WAIT = Duration.ofSeconds(20);
    private static final GenericContainer<?> POSTGRES =
            new GenericContainer<>("postgres:16-alpine")
                    .withEnv("POSTGRES_PASSWORD", "test-password")
                    .withEnv("POSTGRES_DB", "handover_test")
                    .withExposedPorts(5432);

    private HandoverPersistenceFixture() {}

    public static void database(DynamicPropertyRegistry registry) {
        POSTGRES.start();
        registry.add(
                "spring.r2dbc.url",
                () ->
                        "r2dbc:postgresql://127.0.0.1:"
                                + POSTGRES.getMappedPort(5432)
                                + "/handover_test");
        registry.add("spring.r2dbc.username", () -> "postgres");
        registry.add("spring.r2dbc.password", () -> "test-password");
        registry.add("easyorm.dialect", () -> "postgres");
        registry.add("easyorm.default-schema", () -> "public");
    }

    public static Context access(String userId, Set<String> organizations) {
        AssetsHolder holder = mock(AssetsHolder.class);
        when(holder.assertPermission(anyString(), anyCollection(), any()))
                .thenAnswer(
                        call -> {
                            String type = call.getArgument(0);
                            Collection<?> ids = call.getArgument(1);
                            return "organization".equals(type) && organizations.containsAll(ids)
                                    ? Mono.empty()
                                    : Mono.error(new AccessDenyException.NoStackTrace());
                        });
        when(holder.filter(any(Flux.class), any(), any(Function.class), any()))
                .thenAnswer(
                        call -> {
                            Function<Object, ?> id = call.getArgument(2);
                            return ((Flux<?>) call.getArgument(0))
                                    .filter(row -> organizations.contains(id.apply(row)));
                        });
        var user = new SimpleUser();
        user.setId(userId);
        user.setName(userId);
        user.setUsername(userId);
        var auth = new SimpleAuthentication();
        auth.setUser(user);
        return Context.of(AssetsHolder.class, holder, Authentication.class, auth);
    }

    public static void directory(
            HandoverDirectory directory, boolean employee, List<String> direct) {
        reset(directory);
        when(directory.employee())
                .thenReturn(employee ? Mono.empty() : Mono.error(new AccessDenyException()));
        when(directory.currentEmployee())
                .thenReturn(
                        employee
                                ? Mono.just(
                                        new PropertyEmployeeInfo("tester", "tester", null, direct))
                                : Mono.empty());
        when(directory.directOrganizations()).thenReturn(Mono.just(direct));
        List<PropertyOrganizationInfo> external =
                java.util.stream.Stream.of("external-a", "external-b", "external-c")
                        .map(
                                id -> {
                                    PropertyOrganizationInfo org = new PropertyOrganizationInfo();
                                    org.setId(id);
                                    org.setName(id);
                                    org.setOrganizationType(OrganizationType.external);
                                    return org;
                                })
                        .toList();
        when(directory.organizations(anyString())).thenReturn(Flux.fromIterable(external));
        when(directory.external(anyString(), anyString()))
                .thenAnswer(
                        call ->
                                Flux.fromIterable(external)
                                        .filter(org -> org.getId().equals(call.getArgument(1)))
                                        .next()
                                        .switchIfEmpty(
                                                Mono.error(
                                                        new org.hswebframework.web.exception
                                                                .BusinessException(
                                                                "error.handover.organization_invalid",
                                                                400))));
        when(directory.project(anyString()))
                .thenAnswer(
                        call -> {
                            PropertyOrganizationInfo project = new PropertyOrganizationInfo();
                            project.setId(call.getArgument(0));
                            project.setName(call.getArgument(0));
                            return Mono.just(project);
                        });
        when(directory.user(anyString()))
                .thenAnswer(
                        call -> {
                            var user = new PropertyUserInfo();
                            user.setId(call.getArgument(0));
                            user.setName(call.getArgument(0));
                            user.setStatus((byte) 1);
                            return Mono.just(user);
                        });
    }

    @SpringBootConfiguration
    @EnableTransactionManagement(proxyTargetClass = true)
    @EnableEasyormRepository("org.jetlinks.pro.smartpark.handover.entity")
    @Import({
        HandoverCategoryRepository.class,
        HandoverResponsibilityRepository.class,
        HandoverLedgerRepository.class,
        HandoverEquipmentRepository.class,
        HandoverRecordRepository.class,
        HandoverItemRepository.class,
        HandoverProblemRepository.class,
        HandoverPhotoRepository.class,
        HandoverNodeRepository.class,
        HandoverProcessRepository.class,
        HandoverStageLogRepository.class,
        HandoverExportRepository.class,
        HandoverProblemAccess.class,
        HandoverConfigurationService.class,
        HandoverProblemService.class,
        HandoverProcessService.class,
        HandoverStatisticsService.class
    })
    @ImportAutoConfiguration({
        EasyormConfiguration.class,
        R2dbcSqlExecutorConfiguration.class,
        R2dbcAutoConfiguration.class,
        R2dbcTransactionManagerAutoConfiguration.class,
        TransactionAutoConfiguration.class,
        EntityFactoryConfiguration.class
    })
    public static class Config {
        @Bean
        HandoverScope scope() {
            var scope = spy(new HandoverScope(mock(AssetManager.class)));
            doAnswer(
                            call ->
                                    Authentication.currentReactive()
                                            .map(
                                                    auth ->
                                                            new HandoverContext(
                                                                    "tenant",
                                                                    "application",
                                                                    auth.getUser().getId(),
                                                                    auth.getUser().getName())))
                    .when(scope)
                    .current();
            return scope;
        }

        @Bean
        HandoverDirectory directory() {
            return mock(HandoverDirectory.class);
        }

        @Bean
        HandoverFiles files() {
            var files = mock(HandoverFiles.class);
            when(files.validate(any())).thenReturn(Mono.empty());
            return files;
        }

        @Bean
        ObjectMapper mapper() {
            return new ObjectMapper();
        }
    }
}
