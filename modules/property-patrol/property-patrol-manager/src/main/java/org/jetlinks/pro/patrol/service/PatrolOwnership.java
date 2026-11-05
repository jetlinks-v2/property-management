package org.jetlinks.pro.patrol.service;

import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.pro.patrol.entity.*;
import org.jetlinks.pro.patrol.integration.PatrolProjectContext;
import reactor.core.publisher.Mono;
import java.util.Objects;

/** 项目归属的写入 owner；业务引用继承父级项目，历史归属不能通过普通编辑迁移。 */
public final class PatrolOwnership {
    private PatrolOwnership() { }

    static Mono<Void> resolve(PatrolConfigEntity entity, PatrolConfigEntity old,
                              PatrolPointService points) {
        // 预警配置全应用通用；不绑定项目，应用 Scope 和操作权限仍由平台控制。
        if (entity instanceof PatrolWarningEntity) {
            entity.setProjectId(null);
            entity.setProjectName(null);
            entity.setOwnerOrganizationId(null);
            return Mono.empty();
        }
        return PatrolProjectContext.current().flatMap(context -> {
            if (old != null && entity.getProjectId() == null) {
                entity.setProjectId(old.getProjectId());
            }
            Mono<String> project;
            if (entity instanceof PatrolPlanEntity plan)
                project = points.findById(plan.getPointId()).flatMap(point -> Mono.justOrEmpty(point.getProjectId()))
                        .switchIfEmpty(Mono.error(new BusinessException("error.patrol.project_required", 400)));
            else project = Mono.fromCallable(() -> context.projectForWrite(entity.getProjectId()));
            return project.doOnNext(id -> {
                        context.requireProject(id);
                        if (entity.getProjectId() != null && !entity.getProjectId().equals(id))
                            throw new BusinessException("error.patrol.project_mismatch", 400);
                        entity.setProjectId(id);
                        entity.setProjectName(context.projectName(id));
                        entity.setOwnerOrganizationId(null);
                    }).then(Mono.fromRunnable(() -> {
                        if (old != null && !Objects.equals(old.getProjectId(), entity.getProjectId()))
                            throw new BusinessException("error.patrol.project_immutable", 400);
                    }));
        });
    }

    public static Mono<Void> assignSelected(PatrolConfigEntity entity) {
        return PatrolProjectContext.current().doOnNext(context -> {
            String id = context.projectForWrite(entity.getProjectId());
            entity.setProjectId(id);
            entity.setProjectName(context.projectName(id));
            entity.setOwnerOrganizationId(null);
        }).then();
    }
}
