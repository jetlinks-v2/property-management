package org.jetlinks.pro.property.decoration.service;

import lombok.RequiredArgsConstructor;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.crud.query.QueryHelper;
import org.hswebframework.web.id.IDGenerator;
import org.jetlinks.pro.property.decoration.entity.*;
import org.jetlinks.pro.property.decoration.model.DecorationModels;
import org.jetlinks.pro.property.decoration.model.DecorationRequests;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import static org.jetlinks.pro.property.decoration.service.DecorationRules.*;

/** 指派、反馈、直接下发业主整改和角色复核；验收按当前轮次独立聚合。 */
@Service
@RequiredArgsConstructor
public class DecorationTaskService {
    private final DecorationApplicationService applications;
    private final DecorationScope scope;
    private final DecorationTaskRepository tasks;
    private final DecorationConfigurationService configurations;
    private final DecorationAttachmentService attachments;
    private final DecorationAssigneeService assignees;
    private final DecorationRoleService roles;
    private final DecorationInspectionGenerator inspections;
    private final org.springframework.beans.factory.ObjectProvider<org.jetlinks.pro.property.decoration.model.DecorationNotificationGateway> notifications;

    public Mono<PagerResult<DecorationTaskEntity>> query(QueryParamEntity query) {
        return scope.current().flatMap(ctx -> scope.query(query, ctx))
            .flatMap(q -> QueryHelper.queryPager(q, tasks::createQuery));
    }
    /** 个人工作台：可信认证决定执行人/角色，个人条件与统一范围在数据库中AND后分页。 */
    public Mono<PagerResult<DecorationTaskEntity>> mine(QueryParamEntity query) {
        return scope.employee().then(scope.current()).flatMap(ctx -> scope.query(query, ctx).map(q -> {
            var roleIds = ctx.authentication().getDimensions("role").stream().map(d -> d.getId()).toList();
            q.toNestQuery(root -> {
                var n = root.nest();
                n.and("assigneeId", ctx.userId()).or("details", DecorationTaskRoleTermBuilder.TYPE + "$handled", List.of(ctx.userId()));
                if (!roleIds.isEmpty()) {
                    n.orNest().and("kind", "daily").and("status", "pending")
                        .and("details", DecorationTaskRoleTermBuilder.TYPE, roleIds).end();
                    n.orNest().and("kind", "inspectionRecord").and("status", "waiting_review")
                        .and("details", DecorationTaskRoleTermBuilder.TYPE + "$review", roleIds).end();
                }
            });
            return q;
        })).flatMap(q -> QueryHelper.queryPager(q, tasks::createQuery));
    }
    /** 通知受理与整改任务状态分开；没有真实提供方时明确阻止发送。 */
    public Mono<DecorationApplicationEntity> notifyViolation(String id, DecorationRequests.Rectification input) {
        return applications.mutate(id, "notifyViolation", input, false, (row, ctx) -> {
            active(row);
            return task(row, input.getTaskId()).flatMap(task -> {
                require("inspectionRecord".equals(task.getKind()) && Set.of("owner_rectification", "rectification_rejected").contains(task.getStatus()), "invalid_state");
                var provider = notifications.getIfAvailable();
                if (provider == null) return Mono.error(error("notification_unavailable", 503));
                var notice = new org.jetlinks.pro.property.decoration.model.DecorationNotificationGateway.Notice(
                    DecorationApplicationService.identity(id + ":" + input.getRequestId()), id, task.getId(), ctx.applicationId(), ctx.tenantId(),
                    row.getProjectId(), row.getOwnerUserId(), "decoration.rectification");
                return provider.send(notice).switchIfEmpty(Mono.error(error("notification_unavailable", 503)))
                    .doOnNext(receipt -> require(receipt.accepted() && receipt.provider() != null && receipt.reference() != null, "notification_failed")).then();
            });
        });
    }
    /** 管理端来源重发创建独立整改记录，已办理内容保留在原任务。 */
    public Mono<DecorationApplicationEntity> resendRectification(String id, DecorationRequests.Rectification input) {
        return applications.mutate(id, "resendRectification", input, false, (row, ctx) -> {
            active(row); require("pc_admin".equals(row.getSource()), "pc_source_required");
            return task(row, input.getTaskId()).flatMap(old -> {
                require("inspectionRecord".equals(old.getKind()) && Set.of("rectified", "rectification_rejected").contains(old.getStatus()), "invalid_state");
                var copy = newTask(row, "inspectionRecord", ctx); copy.setSourceTaskId(old.getId()); copy.setParentId(old.getParentId());
                copy.setAssigneeId(old.getAssigneeId()); copy.setAssigneeName(old.getAssigneeName()); copy.setStatus("owner_rectification");
                copy.getDetails().setChecks(old.getDetails().getChecks()); copy.getDetails().setResults(old.getDetails().getResults());
                copy.getDetails().setViolations(old.getDetails().getViolations()); copy.getDetails().setFileIds(old.getDetails().getFileIds());
                copy.getDetails().setReviewRoleId(old.getDetails().getReviewRoleId());
                copy.getDetails().setReviewRoleName(old.getDetails().getReviewRoleName());
                copy.getDetails().setFeedback(old.getDetails().getFeedback()); old.setStatus("reissued");
                recordHandling(old, "resendRectification", input, ctx);
                recordHandling(copy, "resendRectification", input, ctx);
                return tasks.insert(copy).then(saveTask(old));
            });
        });
    }

    public Mono<PagerResult<DecorationTaskEntity>> query(String id, QueryParamEntity query, boolean mine) {
        return applications.read(id, mine).flatMap(row -> scope.current().flatMap(ctx -> scope.query(query, ctx)))
            .map(q -> q.toNestQuery(n -> n.and("decorationId", id)).getParam())
            .flatMap(q -> QueryHelper.queryPager(q, tasks::createQuery));
    }
    public Mono<DecorationApplicationEntity> requestAcceptance(String id, DecorationRequests.AcceptanceRequest input, boolean mine) {
        return applications.mutate(id, "requestAcceptance", input, mine, (row, ctx) -> {
            active(row); require(permitReceived(row.getPermit()), "permit_required");
            require(input.getType() == 1 || input.getType() == 2, "invalid_state");
            String prefix = input.getType() == 1 ? "first" : "second";
            boolean retry = (prefix + "_acceptance_failed").equals(row.getStatus());
            require(retry || input.getType() == 1 && row.getAcceptanceType() == 0
                || input.getType() == 2 && "pending_second_acceptance".equals(row.getStatus()), "invalid_state");
            require(mine || "pc_admin".equals(row.getSource()), "pc_source_required");
            int previousRound = row.getAcceptanceRound();
            row.setAcceptanceRound(previousRound + 1); row.setAcceptanceType(input.getType()); row.setStatus(prefix + "_acceptance_waiting");
            // 申请和指派可能相隔多日；时间与申请人只在本轮申请时记录，指派不得覆盖。
            row.setAcceptanceDate(input.getAcceptanceDate()); row.setAcceptanceRequestTime(System.currentTimeMillis());
            row.setAcceptanceRequesterId(ctx.userId()); row.setAcceptanceRequesterName(ctx.userName()); row.setAcceptanceSource(mine ? "h5_owner" : "pc_admin");
            if (mine && !retry) return Mono.empty(); // 业主首次申请等待管理人员指派，不允许客户端自行指派验收人。
            Mono<List<String>> people = retry ? tasks.createQuery().where("decorationId", id).and("round", previousRound)
                .and("kind", input.getType() == 1 ? "firstAcceptance" : "secondAcceptance").fetch().map(DecorationTaskEntity::getAssigneeId).distinct().collectList()
                : Mono.just(input.getAssigneeIds());
            return people.flatMap(ids -> ids.isEmpty() ? Mono.empty() : assign(row, ctx, ids));
        });
    }
    public Mono<DecorationApplicationEntity> assignAcceptance(String id, DecorationRequests.Acceptance input) {
        return applications.mutate(id, "assignAcceptance", input, false, (row, ctx) -> {
            active(row); require(Set.of("first_acceptance_waiting", "second_acceptance_waiting").contains(row.getStatus()), "invalid_state");
            require(!input.getAssigneeIds().isEmpty() && row.getAcceptanceType() == input.getType(), "invalid_state");
            return assign(row, ctx, input.getAssigneeIds());
        });
    }
    private Mono<Void> assign(DecorationApplicationEntity row, DecorationScope.Context ctx, List<String> ids) {
        require(ids.size() <= 30 && ids.size() == ids.stream().distinct().count(), "invalid_assignees");
        String kind = row.getAcceptanceType() == 1 ? "firstAcceptance" : "secondAcceptance";
        return configurations.match(ctx, row.getProjectId(), "checks", row.getDetails().getHouseType(), row.getDetails().getDecorationType(), kind)
            .flatMap(config -> Flux.fromIterable(ids).concatMap(id -> assignees.eligible(id, row.getProjectId(), ctx).map(person -> {
                var task = newTask(row, kind, ctx); task.setAssigneeId(person.id()); task.setAssigneeName(person.name());
                task.getDetails().setChecks(config.getContent().getChecks()); return task;
            })).collectList().flatMap(rows -> tasks.insertBatch(Flux.just(rows)).then()))
            .doOnSuccess(ignore -> row.setStatus(row.getAcceptanceType() == 1 ? "first_acceptance_in_progress" : "second_acceptance_in_progress"));
    }
    public Mono<DecorationApplicationEntity> acceptanceFeedback(String id, DecorationRequests.Feedback input) {
        return applications.mutate(id, "acceptanceFeedback", input, false, (row, ctx) -> {
            active(row);
            return task(row, input.getTaskId()).flatMap(task -> {
                require(Set.of("firstAcceptance", "secondAcceptance").contains(task.getKind()) && task.getRound().equals(row.getAcceptanceRound()), "invalid_state");
                require(task.getAssigneeId().equals(ctx.userId()) && "pending".equals(task.getStatus()), "not_assignee");
                checks(task.getDetails().getChecks(), input.getResults());
                require(!input.isPassed() || input.getResults().stream().allMatch(r -> Boolean.TRUE.equals(r.getQualified())), "invalid_checks");
                fillFeedback(task, input); task.setStatus(input.isPassed() ? "passed" : "failed");
                return validateFiles(task, row, ctx).then(saveTask(task)).then(aggregateAcceptance(row));
            });
        });
    }
    private Mono<Void> aggregateAcceptance(DecorationApplicationEntity row) {
        String kind = row.getAcceptanceType() == 1 ? "firstAcceptance" : "secondAcceptance";
        return tasks.createQuery().where("decorationId", row.getId()).and("round", row.getAcceptanceRound()).and("kind", kind).fetch()
            .take(31).collectList().doOnNext(current -> {
                require(!current.isEmpty() && current.size() <= 30, "invalid_state");
                // 本轮仍有人待反馈时不公布结论，避免提前重提使其待办停留在旧轮次。
                if (current.stream().anyMatch(t -> "pending".equals(t.getStatus()))) return;
                if (current.stream().allMatch(t -> "passed".equals(t.getStatus()))) {
                    row.setStatus(row.getAcceptanceType() == 1 ? "pending_second_acceptance" : "acceptance_passed");
                    if (row.getAcceptanceType() == 1) row.getDetails().setFirstAcceptancePassedTime(System.currentTimeMillis());
                }
                else if (current.stream().anyMatch(t -> "failed".equals(t.getStatus()))) row.setStatus(row.getAcceptanceType() == 1 ? "first_acceptance_failed" : "second_acceptance_failed");
            }).then();
    }
    public Mono<DecorationApplicationEntity> assignInspection(String id, DecorationRequests.Inspection input) {
        return applications.mutate(id, "assignInspection", input, false, (row, ctx) -> {
            active(row); require(row.getPermit() != null, "permit_required"); require(input.getStartTime() <= input.getEndTime(), "invalid_dates");
            require(input.getEndTime() >= System.currentTimeMillis(), "invalid_dates");
            require(input.getInspectionRoles().stream().map(DecorationModels.InspectionRole::roleId).distinct().count() == input.getInspectionRoles().size(), "invalid_assignees");
            return Mono.zip(roles.require(input.getReviewRoleId()),
                Flux.fromIterable(input.getInspectionRoles()).concatMap(config -> roles.require(config.roleId())
                    .map(role -> new DecorationModels.InspectionRole(role.id(), role.name(), config.intervalDays()))).collectList(),
                configurations.match(ctx, row.getProjectId(), "checks", row.getDetails().getHouseType(), row.getDetails().getDecorationType(), "daily"))
                .flatMap(tuple -> {
                    var plan = new DecorationModels.TaskDetails();
                    plan.setInspectionRoles(tuple.getT2()); plan.setStartTime(input.getStartTime()); plan.setEndTime(input.getEndTime());
                    plan.setReviewRoleId(tuple.getT1().id()); plan.setReviewRoleName(tuple.getT1().name()); plan.setChecks(tuple.getT3().getContent().getChecks());
                    plan.setAssignedById(ctx.userId()); plan.setAssignedByName(ctx.userName()); row.setInspectionPlan(plan);
                    // 首次指派不补造过去任务；当天已存在的任务保留原角色/检查项，变更作用于后续日期。
                    row.setNextInspectionTime(DecorationInspectionGenerator.dayStart(Math.max(input.getStartTime(), System.currentTimeMillis())));
                    return inspections.generateLocked(row, System.currentTimeMillis()).then();
                });
        });
    }
    public Mono<DecorationApplicationEntity> inspectionFeedback(String id, DecorationRequests.Feedback input) {
        return applications.mutate(id, "inspectionFeedback", input, false, (row, ctx) -> {
            active(row);
            return task(row, input.getTaskId()).flatMap(task -> {
                require("daily".equals(task.getKind()) && "pending".equals(task.getStatus()), "invalid_state");
                require(task.getDetails().getInspectionRoles().stream().anyMatch(role -> ctx.authentication().getDimensions("role").stream()
                    .anyMatch(dimension -> dimension.getId().equals(role.roleId()))), "not_assignee");
                long now = System.currentTimeMillis();
                require(now >= task.getDetails().getStartTime() && now <= task.getDetails().getEndTime(), "inspection_time_invalid");
                checks(task.getDetails().getChecks(), input.getResults());
                require(input.isPassed() && input.getViolations().isEmpty() && input.getResults().stream().allMatch(r -> Boolean.TRUE.equals(r.getQualified()))
                    || !input.isPassed() && !input.getViolations().isEmpty(), "violation_required");
                var record = newTask(row, "inspectionRecord", ctx); record.setParentId(task.getId()); record.setAssigneeId(ctx.userId()); record.setAssigneeName(ctx.userName());
                record.getDetails().setReviewRoleId(task.getDetails().getReviewRoleId());
                record.getDetails().setReviewRoleName(task.getDetails().getReviewRoleName());
                record.getDetails().setChecks(task.getDetails().getChecks()); fillFeedback(record, input); record.getDetails().setViolations(input.getViolations());
                // 任务提交即对业主可见；外部消息受理不代替业务下发，也不阻断巡检落库。
                record.setStatus(input.isPassed() ? "passed" : "owner_rectification");
                task.setStatus("completed"); task.setAssigneeId(ctx.userId()); task.setAssigneeName(ctx.userName());
                return validateFiles(record, row, ctx).then(tasks.insert(record)).then(saveTask(task));
            });
        });
    }
    public Mono<DecorationApplicationEntity> submitRectification(String id, DecorationRequests.Rectification input, boolean mine) {
        return applications.mutate(id, "submitRectification", input, mine, (row, ctx) -> {
            active(row); require(mine || "pc_admin".equals(row.getSource()), "pc_source_required");
            return task(row, input.getTaskId()).flatMap(task -> {
                require("inspectionRecord".equals(task.getKind()) && Set.of("owner_rectification", "rectification_rejected").contains(task.getStatus()), "invalid_state");
                require(!input.getFileIds().isEmpty() || input.getRemark() != null && !input.getRemark().isBlank(), "rectification_required");
                task.getDetails().setRectificationFiles(input.getFileIds()); task.getDetails().setRectificationRemark(input.getRemark()); task.setStatus("waiting_review");
                recordHandling(task, "submitRectification", input, ctx);
                return attachments.validate(input.getFileIds(), row.getProjectId(), id, ctx, false).then(attachments.consume(input.getFileIds(), id, ctx)).then(saveTask(task));
            });
        });
    }
    public Mono<DecorationApplicationEntity> reviewRectification(String id, DecorationRequests.Rectification input) {
        return applications.mutate(id, "reviewRectification", input, false, (row, ctx) -> {
            active(row);
            return task(row, input.getTaskId()).flatMap(task -> {
                require("inspectionRecord".equals(task.getKind()) && "waiting_review".equals(task.getStatus()), "invalid_state");
                role(ctx, task.getDetails().getReviewRoleId());
                if (!input.isPassed()) require(input.getRemark() != null && !input.getRemark().isBlank(), "remark_required");
                task.setStatus(input.isPassed() ? "rectified" : "rectification_rejected");
                recordHandling(task, "reviewRectification", input, ctx); return saveTask(task);
            });
        });
    }
    public Mono<DecorationApplicationEntity> reassignRole(String id, DecorationRequests.Rectification input) {
        return applications.mutate(id, "reassignReviewRole", input, false, (row, ctx) -> {
            active(row); require(input.getReviewRoleId() != null && !input.getReviewRoleId().isBlank(), "invalid_assignees");
            return task(row, input.getTaskId()).flatMap(task -> {
                require(Set.of("owner_rectification", "rectification_rejected", "waiting_review").contains(task.getStatus()), "invalid_state");
                return roles.require(input.getReviewRoleId()).flatMap(role -> {
                    task.getDetails().setReviewRoleId(role.id()); task.getDetails().setReviewRoleName(role.name());
                    recordHandling(task, "reassignReviewRole", input, ctx); return saveTask(task);
                });
            });
        });
    }
    private Mono<DecorationTaskEntity> task(DecorationApplicationEntity row, String id) {
        return tasks.findById(id).filter(t -> t.getDecorationId().equals(row.getId()) && t.getApplicationId().equals(row.getApplicationId()) && t.getTenantId().equals(row.getTenantId()))
            .switchIfEmpty(Mono.error(error("task_not_found", 404)));
    }
    static DecorationTaskEntity newTask(DecorationApplicationEntity row, String kind, DecorationScope.Context ctx) {
        var task = new DecorationTaskEntity(); task.setId(IDGenerator.MD5.generate()); task.setApplicationId(ctx.applicationId()); task.setTenantId(ctx.tenantId());
        task.setProjectId(row.getProjectId()); task.setProjectName(row.getProjectName()); task.setNumber(row.getNumber()); task.setSpaceName(row.getSpaceName()); task.setOwnerName(row.getOwnerName()); task.setDecorationId(row.getId()); task.setKind(kind); task.setRound(row.getAcceptanceRound()); task.setVersion(1L);
        task.setContactPhone(row.getContactPhone()); task.setConstructionUnit(row.getConstructionUnit()); task.setConstructionManager(row.getConstructionManager()); task.setManagerPhone(row.getManagerPhone());
        task.setHouseType(row.getHouseType()); task.setDecorationType(row.getDecorationType()); task.setApplicationTime(row.getCreateTime()); task.setDecorationStartTime(row.getStartTime()); task.setCompletionTime(row.getCompletionTime());
        task.setCreatorId(ctx.userId()); task.setCreatorName(ctx.userName());
        if ("firstAcceptance".equals(kind) || "secondAcceptance".equals(kind)) {
            task.setAcceptanceDate(row.getAcceptanceDate()); task.setAcceptanceRequestTime(row.getAcceptanceRequestTime());
            task.setAcceptanceRequesterId(row.getAcceptanceRequesterId()); task.setAcceptanceRequesterName(row.getAcceptanceRequesterName()); task.setAcceptanceSource(row.getAcceptanceSource());
        }
        task.setCreateTime(System.currentTimeMillis()); task.setModifyTime(task.getCreateTime()); task.setStatus("pending"); task.setDetails(new DecorationModels.TaskDetails()); return task;
    }
    private void fillFeedback(DecorationTaskEntity task, DecorationRequests.Feedback input) {
        task.getDetails().setFeedback(input.getRemark()); task.getDetails().setResults(input.getResults()); task.getDetails().setFileIds(input.getFileIds()); task.getDetails().setDeadline(input.getDeadline());
    }
    private void recordHandling(DecorationTaskEntity task, String action, DecorationRequests.Rectification input, DecorationScope.Context ctx) {
        var details = task.getDetails();
        details.getHandlingRecords().add(new DecorationModels.TaskHandlingRecord(action, task.getStatus(), ctx.userId(), ctx.userName(),
            System.currentTimeMillis(), input.getRemark(), "submitRectification".equals(action) ? List.copyOf(input.getFileIds()) : List.of(),
            details.getReviewRoleId(), details.getReviewRoleName()));
    }
    private Mono<Void> saveTask(DecorationTaskEntity task) {
        long version = task.getVersion(); task.setVersion(version + 1); task.setModifyTime(System.currentTimeMillis());
        return tasks.createUpdate().set(task).where("id", task.getId()).and("version", version).execute().doOnNext(n -> require(n == 1, "version_conflict")).then();
    }
    private Mono<Void> validateFiles(DecorationTaskEntity task, DecorationApplicationEntity row, DecorationScope.Context ctx) {
        var ids = new ArrayList<>(task.getDetails().getFileIds()); task.getDetails().getResults().forEach(r -> ids.addAll(r.getFileIds()));
        task.getDetails().getViolations().forEach(v -> { require(v.getLocation() != null && v.getRectification() != null, "violation_required"); ids.addAll(v.getFileIds()); });
        return attachments.validate(ids, row.getProjectId(), row.getId(), ctx, false).then(attachments.consume(ids, row.getId(), ctx));
    }
    private void role(DecorationScope.Context ctx, String roleId) {
        require(ctx.authentication().getDimensions("role").stream().anyMatch(role -> role.getId().equals(roleId)), "role_required");
    }
}
