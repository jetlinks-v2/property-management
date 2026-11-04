package org.jetlinks.pro.smartpark.handover.service;

import lombok.RequiredArgsConstructor;

import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.authorization.exception.AccessDenyException;
import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.core.command.CommandSupport;
import org.jetlinks.pro.assets.impl.supports.OrgAssetsAccessSupport;
import org.jetlinks.pro.command.CommandSupportManagerProviders;
import org.jetlinks.pro.property.base.command.*;
import org.jetlinks.pro.property.base.organization.*;
import org.jetlinks.pro.property.base.space.PropertySpaceInfo;
import org.jetlinks.pro.property.base.user.*;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/** Read-only base-core commands preserve current authentication, tenant and application scope. */
@Service
@RequiredArgsConstructor
public class HandoverDirectory {
    private final HandoverScope scope;

    private Mono<CommandSupport> support(String id) {
        return CommandSupportManagerProviders.getCommandSupport(id)
                .switchIfEmpty(
                        Mono.error(
                                new BusinessException(
                                        "error.handover.directory_unavailable", 503)));
    }

    public Flux<PropertyOrganizationInfo> projects() {
        return scope.current()
                .thenMany(
                        support(QueryPropertyProjectsCommand.SERVICE_ID)
                                .flatMapMany(s -> s.execute(new QueryPropertyProjectsCommand())));
    }

    public Mono<PropertyOrganizationInfo> project(String id) {
        return projects()
                .filter(p -> p.getId().equals(id))
                .next()
                .switchIfEmpty(
                        Mono.error(new BusinessException("error.handover.project_invalid", 400)));
    }

    public Flux<PropertyOrganizationInfo> organizations(String projectId) {
        return scope.current()
                .then(
                        scope.project(
                                projectId, org.jetlinks.pro.assets.crud.CrudAssetPermission.read))
                .thenMany(
                        support(QueryPropertyOrganizationTreeCommand.SERVICE_ID)
                                .flatMapMany(
                                        s -> s.execute(new QueryPropertyOrganizationTreeCommand())))
                .expand(
                        p ->
                                Flux.fromIterable(
                                        p.getChildren() == null ? List.of() : p.getChildren()))
                .filter(p -> p.getOrganizationType() == OrganizationType.external);
    }

    public Mono<PropertyOrganizationInfo> external(String projectId, String id) {
        return organizations(projectId)
                .filter(p -> p.getId().equals(id))
                .next()
                .switchIfEmpty(
                        Mono.error(
                                new BusinessException("error.handover.organization_invalid", 400)));
    }

    public Mono<PropertyEmployeeInfo> currentEmployee() {
        return support(GetCurrentPropertyEmployeeCommand.SERVICE_ID)
                .flatMap(s -> s.execute(new GetCurrentPropertyEmployeeCommand()));
    }

    public Mono<Void> employee() {
        return currentEmployee().switchIfEmpty(Mono.error(new AccessDenyException())).then();
    }

    public Mono<PropertyUserInfo> user(String id) {
        var command = new QueryPropertyUsersCommand();
        command.setIds(List.of(id));
        return support(QueryPropertyUsersCommand.SERVICE_ID)
                .flatMapMany(s -> s.execute(command))
                .filter(u -> u.getStatus() != null && u.getStatus() == 1)
                .next()
                .switchIfEmpty(
                        Mono.error(new BusinessException("error.handover.user_invalid", 400)));
    }

    public Mono<PagerResult<PropertyEmployeeInfo>> users(
            String projectId, String keyword, int pageIndex, int pageSize) {
        var query = new QueryParamEntity();
        query.setPageIndex(Math.max(0, pageIndex));
        query.setPageSize(Math.min(200, Math.max(1, pageSize)));
        if (HandoverRules.hasText(keyword)) query.toQuery().like("name", "%" + keyword + "%");
        var command = new QueryPropertyEmployeePageCommand();
        command.setQuery(query);
        return scope.project(projectId, org.jetlinks.pro.assets.crud.CrudAssetPermission.read)
                .then(
                        support(QueryPropertyEmployeePageCommand.SERVICE_ID)
                                .flatMap(s -> s.execute(command)));
    }

    public Flux<PropertySpaceInfo> spaces(String projectId) {
        var command = new QueryPropertySpacesCommand();
        command.setProjectId(projectId);
        return scope.project(projectId, org.jetlinks.pro.assets.crud.CrudAssetPermission.read)
                .thenMany(
                        support(QueryPropertySpacesCommand.SERVICE_ID)
                                .flatMapMany(s -> s.execute(command)));
    }

    public Mono<PropertySpaceInfo> space(String projectId, String id) {
        return spaces(projectId)
                .filter(p -> id.equals(p.getId()))
                .next()
                .switchIfEmpty(
                        Mono.error(new BusinessException("error.handover.space_invalid", 400)));
    }

    public Mono<List<String>> directOrganizations() {
        return Authentication.currentReactive()
                .switchIfEmpty(Mono.error(new AccessDenyException()))
                .map(
                        auth ->
                                auth.getDimensions("org").stream()
                                        .filter(
                                                d ->
                                                        d.getOption(
                                                                        OrgAssetsAccessSupport
                                                                                .IS_DIRECT)
                                                                .map(
                                                                        v ->
                                                                                Boolean.TRUE.equals(
                                                                                                v)
                                                                                        || "true"
                                                                                                .equals(
                                                                                                        v
                                                                                                                .toString()))
                                                                .orElse(false))
                                        .map(d -> d.getId())
                                        .distinct()
                                        .toList());
    }

    public Mono<Map<String, Object>> me() {
        return Mono.zip(scope.current(), directOrganizations(), currentEmployee().hasElement())
                .map(
                        t ->
                                Map.of(
                                        "id",
                                        t.getT1().userId(),
                                        "name",
                                        t.getT1().userName(),
                                        "organizationIds",
                                        t.getT2(),
                                        "employee",
                                        t.getT3()));
    }
}
