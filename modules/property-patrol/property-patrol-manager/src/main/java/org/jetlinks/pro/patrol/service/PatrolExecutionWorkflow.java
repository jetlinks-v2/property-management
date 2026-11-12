package org.jetlinks.pro.patrol.service;

import lombok.AllArgsConstructor;

import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.authorization.ReactiveAuthenticationHolder;
import org.hswebframework.web.exception.BusinessException;
import org.hswebframework.web.exception.NotFoundException;
import org.jetlinks.core.trace.MonoTracer;
import org.jetlinks.pro.assets.AssetsHolder;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.patrol.asset.PatrolAssetType;
import org.jetlinks.pro.patrol.domain.ExecutionPolicy;
import org.jetlinks.pro.patrol.entity.PatrolExecutionEntity;
import org.jetlinks.pro.patrol.entity.PatrolPlanEntity;
import org.jetlinks.pro.patrol.entity.PatrolPointEntity;
import org.jetlinks.pro.patrol.entity.PatrolRole;
import org.jetlinks.pro.patrol.entity.PatrolRosterEntity;
import org.jetlinks.pro.patrol.entity.PatrolTaskEntity;
import org.jetlinks.pro.patrol.integration.SaasPersonDirectory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;

import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/** 执行唯一事务 owner：资格、CAS 计数和执行事实一起提交；重试只恢复同一个已提交请求。 */
@Service
@AllArgsConstructor
public class PatrolExecutionWorkflow {
    private final PatrolTaskService tasks;
    private final PatrolPlanService plans;
    private final PatrolPointService points;
    private final PatrolRoleService roles;
    private final PatrolRosterService rosters;
    private final PatrolExecutionService executions;
    private final SaasPersonDirectory people;
    private final PatrolAttachmentService attachments;
    private final TransactionalOperator transaction;

    public record Submission(
            String taskId,
            String requestId,
            List<PatrolExecutionEntity.Answer> answers,
            String remark,
            Long collectedTime,
            PatrolExecutionEntity.NetworkStatus networkStatus) {}

    public Mono<PatrolExecutionEntity> submit(Submission submission) {
        return ReactiveAuthenticationHolder.get()
                .flatMap(
                        auth ->
                                tasks.findById(submission.taskId())
                                        .switchIfEmpty(Mono.error(NotFoundException::new))
                                        .flatMap(task -> authorizedSubmit(task, auth, submission)))
                .as(MonoTracer.create("/patrol/executions/submit"));
    }

    private Mono<PatrolExecutionEntity> authorizedSubmit(
            PatrolTaskEntity task, Authentication auth, Submission submission) {
        String userId = auth.getUser().getId();
        String id =
                UUID.nameUUIDFromBytes(
                                (userId + "|" + submission.requestId())
                                        .getBytes(StandardCharsets.UTF_8))
                        .toString();
        // 重放也先复核当前权限与有效成员；撤权不能凭旧请求号读取执行记录。
        return AssetsHolder.assertPermission(
                        PatrolAssetType.patrolPlan, task.getPlanId(), CrudAssetPermission.read)
                .then(people.assertMembers(List.of(userId)))
                .then(existingOrWrite(task, auth, id, submission));
    }

    private Mono<PatrolExecutionEntity> existingOrWrite(
            PatrolTaskEntity task, Authentication auth, String id, Submission submission) {
        Mono<PatrolExecutionEntity> insert =
                Mono.defer(() -> write(task, auth, id, submission))
                        .as(transaction::transactional)
                        // 先结束失败事务，再读取另一请求可能已提交的相同幂等键。
                        .onErrorResume(
                                error ->
                                        DatabaseConflicts.isDuplicateKey(error)
                                                || error instanceof BusinessException,
                                error ->
                                        executions
                                                .findById(id)
                                                .switchIfEmpty(Mono.error(error))
                                                .flatMap(record -> replay(record, submission)));
        return executions
                .findById(id)
                .flatMap(record -> replay(record, submission))
                .switchIfEmpty(insert);
    }

    private Mono<PatrolExecutionEntity> replay(
            PatrolExecutionEntity record, Submission submission) {
        if (!record.getTaskId().equals(submission.taskId())
                || !Objects.equals(record.getAnswers(), submission.answers())
                || !Objects.equals(record.getRemark(), submission.remark())
                || !Objects.equals(record.getCollectedTime(), submission.collectedTime())
                || !Objects.equals(record.getNetworkStatus(), submission.networkStatus()))
            return Mono.error(new BusinessException("error.patrol.execution.idempotency_conflict", 409));
        return Mono.just(record);
    }

    private Mono<PatrolExecutionEntity> write(
            PatrolTaskEntity task, Authentication auth, String id, Submission submission) {
        Instant now = Instant.now();
        return checkEligibility(task, auth.getUser().getId(), now)
                .flatMap(scopeKey -> attachments.assertReferences(
                        submission.answers().stream().map(PatrolExecutionEntity.Answer::attachmentId)
                                .filter(fileId -> fileId != null && !fileId.isBlank()).toList(),
                        org.jetlinks.pro.patrol.entity.PatrolAttachmentEntity.Purpose.evidence,
                        scopeKey, auth.getUser().getId(), task.getProjectId()))
                .then(Mono.fromCallable(() -> buildRecord(task, auth, id, submission, now)))
                .flatMap(
                        record ->
                                tasks.createUpdate()
                                        .set(
                                                PatrolTaskEntity::getCompletedCount,
                                                task.getCompletedCount() + 1)
                                        .set(
                                                PatrolTaskEntity::getLastExecutionTime,
                                                now.toEpochMilli())
                                        .where(PatrolTaskEntity::getId, task.getId())
                                        .and(
                                                PatrolTaskEntity::getCompletedCount,
                                                task.getCompletedCount())
                                        .execute()
                                        .flatMap(
                                                changed ->
                                                        changed == 1
                                                                ? executions
                                                                        .insert(record)
                                                                        .thenReturn(record)
                                                                : Mono.error(
                                                                        new BusinessException("error.patrol.execution.concurrent", 409))));
    }

    /** 列表只预检；提交仍在事务中重新校验，不能把页面状态当成执行凭据。 */
    Mono<String> checkEligibility(PatrolTaskEntity task, String userId, Instant now) {
        return checkEligibility(task, userId, now, plans.findById(task.getPlanId()),
                points.findById(task.getPointId()), scopeKey -> checkRoster(task, scopeKey, userId, now));
    }

    /** 只复用本次已授权列表的参考数据，提交入口仍实时读取；不跨用户、应用或请求缓存。 */
    Mono<Function<PatrolTaskEntity, Mono<String>>> listEligibility(List<PatrolTaskEntity> tasks, String userId, Instant now) {
        return Mono.zip(
                Flux.fromIterable(tasks).map(PatrolTaskEntity::getPlanId).distinct().buffer(200)
                        .concatMap(plans::findById).collectMap(PatrolPlanEntity::getId),
                Flux.fromIterable(tasks).map(PatrolTaskEntity::getPointId).distinct().buffer(200)
                        .concatMap(points::findById).collectMap(PatrolPointEntity::getId))
                .map(data -> {
                    record RosterKey(String scopeKey, String projectId, LocalDate date) {}
                    Map<RosterKey, Mono<List<PatrolRosterEntity>>> shifts = new ConcurrentHashMap<>();
                    return task -> checkEligibility(task, userId, now,
                            Mono.justOrEmpty(data.getT1().get(task.getPlanId())),
                            Mono.justOrEmpty(data.getT2().get(task.getPointId())), scopeKey -> {
                                if (!hasRole(task)) return applyExecutionPolicy(task, List.of(), userId, now);
                                var key = new RosterKey(scopeKey, task.getProjectId(), startDate(task));
                                return shifts.computeIfAbsent(key, ignored -> rosterRows(task, scopeKey).cache())
                                        .flatMap(rows -> applyExecutionPolicy(task, rows, userId, now));
                            });
                });
    }

    private Mono<String> checkEligibility(PatrolTaskEntity task, String userId, Instant now,
                                          Mono<PatrolPlanEntity> plan, Mono<PatrolPointEntity> point,
                                          Function<String, Mono<Void>> roster) {
        return Mono.zip(
                        plan,
                        point,
                        hasRole(task) ? roles.currentRole(task.getRoleId()).map(role -> role.isEnabled()
                                && role.getUserIds().contains(userId)) : Mono.just(true))
                .switchIfEmpty(
                        Mono.error(new BusinessException("error.patrol.reference.unavailable", 400)))
                .flatMap(
                        data -> {
                            // 全员任务不要求角色；指定任务仍先核实本人角色，排班不能代替角色资格。
                            if (!data.getT3()) {
                                return Mono.error(new BusinessException("error.patrol.execution.unassigned", 400));
                            }
                            if (!data.getT1().isEnabled()
                                    || !data.getT2().isEnabled()) {
                                return Mono.error(
                                        new BusinessException("error.patrol.reference.unavailable", 400));
                            }
                            return roster.apply(data.getT1().getScopeKey())
                                    .thenReturn(data.getT1().getScopeKey());
                        });
    }

    private Mono<Void> checkRoster(
            PatrolTaskEntity task,
            String scopeKey,
            String userId,
            Instant now) {
        // 老系统全员任务不受排班限制；指定任务按开始日、项目判定，班次不要求与任务同角色。
        if (!hasRole(task)) return applyExecutionPolicy(task, List.of(), userId, now);
        return rosterRows(task, scopeKey).flatMap(rows -> applyExecutionPolicy(task, rows, userId, now));
    }

    private static LocalDate startDate(PatrolTaskEntity task) {
        return Instant.ofEpochMilli(task.getStartTime()).atZone(ZoneId.of("Asia/Shanghai")).toLocalDate();
    }

    private Mono<List<PatrolRosterEntity>> rosterRows(PatrolTaskEntity task, String scopeKey) {
        return rosters.createQuery()
                .where(PatrolRosterEntity::getScopeKey, scopeKey)
                .and(PatrolRosterEntity::getBusinessDate, startDate(task))
                .and(PatrolRosterEntity::getProjectId, task.getProjectId())
                .and(PatrolRosterEntity::isEnabled, true)
                .fetch()
                .take(1001)
                .collectList();
    }

    private Mono<Void> applyExecutionPolicy(
            PatrolTaskEntity task,
            List<PatrolRosterEntity> rows,
            String userId,
            Instant now) {
        if (rows.size() > 1000)
            return Mono.error(new BusinessException("error.patrol.roster.too_many", 400));
        boolean inShift =
                rows.stream()
                        .anyMatch(
                                row ->
                                        !row.isRest()
                                                && userId.equals(row.getUserId())
                                                && now.toEpochMilli() >= row.getStartTime()
                                                && now.toEpochMilli() < row.getEndTime());
        Instant last =
                task.getLastExecutionTime() == null
                        ? null
                        : Instant.ofEpochMilli(task.getLastExecutionTime());
        try {
            ExecutionPolicy.check(
                    now,
                    Instant.ofEpochMilli(task.getStartTime()),
                    Instant.ofEpochMilli(task.getEndTime()),
                    task.getCompletedCount(),
                    task.getRequiredCount(),
                    last,
                    task.getCooldownMinutes(),
                    true,
                    task.getRosterPolicy(),
                    !rows.isEmpty(),
                    inShift);
            return Mono.empty();
        } catch (IllegalArgumentException error) {
            return Mono.error(new BusinessException(error.getMessage(), 400));
        }
    }

    private static boolean hasRole(PatrolTaskEntity task) {
        return task.getRoleId() != null && !task.getRoleId().isBlank();
    }

    private PatrolExecutionEntity buildRecord(
            PatrolTaskEntity task,
            Authentication auth,
            String id,
            Submission submission,
            Instant now) {
        var evaluation = PatrolInspection.evaluate(task.getStandardSnapshot(), submission.answers());
        var record = new PatrolExecutionEntity();
        record.setId(id);
        record.setTaskId(task.getId());
        record.setProjectId(task.getProjectId());
        record.setProjectName(task.getProjectName());
        record.setTaskGroupId(task.getTaskGroupId());
        record.setBelongDate(task.getBelongDate());
        record.setPeriodNo(task.getPeriodNo());
        record.setRuleVersion(task.getRuleVersion());
        record.setPeriodStartTime(task.getStartTime());
        record.setPeriodEndTime(task.getEndTime());
        record.setCategoryId(task.getCategoryId());
        record.setCategoryName(task.getCategoryName());
        record.setLevelId(task.getLevelId());
        record.setLevelName(task.getLevelName());
        record.setPlanId(task.getPlanId());
        record.setPointId(task.getPointId());
        record.setPointName(task.getPointName());
        record.setUserId(auth.getUser().getId());
        record.setUserName(auth.getUser().getName());
        record.setRequestId(submission.requestId());
        record.setReceiveTime(now.toEpochMilli());
        record.setCollectedTime(submission.collectedTime());
        record.setNetworkStatus(submission.networkStatus());
        record.setRemark(submission.remark());
        record.setAnswers(List.copyOf(submission.answers()));
        record.setStandardSnapshot(task.getStandardSnapshot());
        record.setItemResults(evaluation.items());
        record.setResult(evaluation.result());
        return record;
    }
}
