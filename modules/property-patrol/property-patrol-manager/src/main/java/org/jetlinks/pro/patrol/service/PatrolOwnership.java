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
                              PatrolPointService points, PatrolPostService posts) {
        return PatrolProjectContext.current().flatMap(context -> {
            if (old != null && entity.getProjectId() == null && entity.getOwnerOrganizationId() == null) {
                entity.setProjectId(old.getProjectId());
                entity.setOwnerOrganizationId(old.getOwnerOrganizationId());
            }
            Mono<String> project;
            if (entity instanceof PatrolPlanEntity plan)
                project = points.findById(plan.getPointId()).flatMap(point -> Mono.justOrEmpty(point.getProjectId()))
                        .switchIfEmpty(Mono.error(new BusinessException("error.patrol.project_required", 400)));
            else if (entity instanceof PatrolRosterEntity roster)
                project = posts.findById(roster.getPostId()).flatMap(post -> Mono.justOrEmpty(post.getProjectId()))
                        .switchIfEmpty(Mono.error(new BusinessException("error.patrol.project_required", 400)));
            else if (entity instanceof PatrolPointEntity || entity instanceof PatrolPostEntity)
                project = Mono.fromCallable(() -> context.projectForWrite(entity.getProjectId()));
            else if (entity.getProjectId() != null || context.selectedProject() != null && entity.getOwnerOrganizationId() == null)
                project = Mono.fromCallable(() -> context.projectForWrite(entity.getProjectId()));
            else project = Mono.empty();
            return project.doOnNext(id -> {
                        context.requireProject(id);
                        if (entity.getProjectId() != null && !entity.getProjectId().equals(id))
                            throw new BusinessException("error.patrol.project_mismatch", 400);
                        entity.setProjectId(id);
                        entity.setProjectName(context.projectName(id));
                        entity.setOwnerOrganizationId(null);
                    }).then(Mono.fromRunnable(() -> {
                        if (entity.getProjectId() == null) {
                            context.requireOrganization(entity.getOwnerOrganizationId());
                            entity.setProjectName(null);
                        }
                        if (old != null && (!Objects.equals(old.getProjectId(), entity.getProjectId())
                                || !Objects.equals(old.getOwnerOrganizationId(), entity.getOwnerOrganizationId())))
                            throw new BusinessException("error.patrol.project_immutable", 400);
                    }));
        });
    }

    public static Mono<Void> assignSelected(PatrolConfigEntity entity) {
        return PatrolProjectContext.current().doOnNext(context -> {
            if (entity instanceof PatrolAttachmentEntity attachment
                    && attachment.getPurpose() == PatrolAttachmentEntity.Purpose.example
                    && context.attachmentOwner() != null) {
                entity.setOwnerOrganizationId(context.requireOrganization(context.attachmentOwner()));
                return;
            }
            String id = context.projectForWrite(entity.getProjectId());
            entity.setProjectId(id);
            entity.setProjectName(context.projectName(id));
            entity.setOwnerOrganizationId(null);
        }).then();
    }
}
