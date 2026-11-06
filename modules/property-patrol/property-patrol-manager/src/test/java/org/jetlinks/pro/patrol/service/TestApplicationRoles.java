package org.jetlinks.pro.patrol.service;

import org.jetlinks.pro.patrol.entity.PatrolRole;
import org.jetlinks.pro.patrol.integration.PatrolScope;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.exception.BusinessException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.util.LinkedHashMap;
import java.util.Map;

/** 工作流测试的外部角色目录替身；业务事实仍使用真实 R2DBC 库，不建本地角色表。 */
final class TestApplicationRoles extends PatrolRoleService {
    private final Map<String, PatrolRole> rows = new LinkedHashMap<>();
    TestApplicationRoles() { super(new PatrolScope()); }
    void put(PatrolRole role) { rows.put(role.getId(), role); }
    PatrolRole get(String id) { return rows.get(id); }
    @Override public Mono<PatrolRole> findById(String id) { return Mono.defer(() -> Mono.justOrEmpty(rows.get(id))); }
    @Override public Flux<PatrolRole> query(QueryParamEntity query) { return Flux.defer(() -> Flux.fromIterable(rows.values())); }
    @Override public Mono<PatrolRole> currentRole(String id) {
        return findById(id).filter(row -> row.isEnabled() && row.getUserIds().contains("alice"))
                .switchIfEmpty(Mono.error(new BusinessException("error.patrol.execution.unassigned", 400)));
    }
}
