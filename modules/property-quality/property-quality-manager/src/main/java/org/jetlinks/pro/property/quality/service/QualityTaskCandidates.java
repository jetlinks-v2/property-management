package org.jetlinks.pro.property.quality.service;

import lombok.RequiredArgsConstructor;
import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.authorization.exception.AccessDenyException;
import org.jetlinks.pro.property.base.user.PropertyUserInfo;
import org.jetlinks.pro.property.quality.entity.QualityTaskEntity;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

/** 新工单尚无资产 ID：以候选人的实时应用动作及基础项目权限验证初次分配。 */
@Service
@RequiredArgsConstructor
public class QualityTaskCandidates {
    private final QualityScope scope;
    private final QualityDirectory directory;
    private final QualityExecution execution;

    public Flux<PropertyUserInfo> candidates(String projectId, String role) {
        return scope.current().flatMapMany(context -> directory.project(projectId).flatMapMany(project -> {
            var task = QualityScope.initialize(new QualityTaskEntity(), context);
            task.setProjectId(project.getId());
            return QualityCandidateScan.filter(directory.employees(task.getApplicationId()), person -> eligible(task, person, role));
        }));
    }

    public Flux<Map<String, String>> roles(String projectId) {
        return scope.current().flatMapMany(context -> {
            var task = QualityScope.initialize(new QualityTaskEntity(), context);
            task.setProjectId(projectId);
            return candidates(projectId, null).flatMap(person -> execution.asUser(task, person.getId(), () ->
                Authentication.currentReactive().map(auth -> auth.getDimensions("role").stream()
                    .map(role -> Map.of("id", role.getId(), "name", role.getName())).toList()))
                .flatMapIterable(roles -> roles)).distinct(role -> role.get("id"));
        });
    }

    public Mono<PropertyUserInfo> assertEligible(QualityTaskEntity task, String userId) {
        return directory.employee(userId).filterWhen(person -> eligible(task, person, task.getCheckRole()))
            .switchIfEmpty(Mono.error(new AccessDenyException()));
    }

    private Mono<Boolean> eligible(QualityTaskEntity task, PropertyUserInfo person, String role) {
        return execution.asUser(task, person.getId(), () -> Authentication.currentReactive()
            .filter(auth -> auth.hasPermission("quality-work-order", "inspect"))
            .filter(auth -> role == null || role.isBlank() || auth.getDimensions("role").stream()
                .anyMatch(dimension -> role.equals(dimension.getId())))
            .flatMap(auth -> directory.project(task.getProjectId())).map(project -> true))
            .defaultIfEmpty(false).onErrorReturn(AccessDenyException.class, false)
            .onErrorResume(org.hswebframework.web.exception.BusinessException.class, error -> error.getStatus()==400?Mono.just(false):Mono.error(error));
    }
}
