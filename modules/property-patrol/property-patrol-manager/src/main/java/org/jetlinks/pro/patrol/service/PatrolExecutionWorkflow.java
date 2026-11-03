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
import org.jetlinks.pro.patrol.entity.PatrolPostEntity;
import org.jetlinks.pro.patrol.entity.PatrolRosterEntity;
import org.jetlinks.pro.patrol.entity.PatrolTaskEntity;
import org.jetlinks.pro.patrol.integration.SaasPersonDirectory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;

import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** 执行唯一事务 owner：资格、CAS 计数和执行事实一起提交；重试只恢复同一个已提交请求。 */
@Service
@AllArgsConstructor
public class PatrolExecutionWorkflow {
    private final PatrolTaskService tasks;
    private final PatrolPlanService plans;
    private final PatrolPointService points;
    private final PatrolPostService posts;
    private final PatrolRosterService rosters;
    private final PatrolExecutionService executions;
    private final SaasPersonDirectory people;
    private final TransactionalOperator transaction;

    public record Submission(
            String taskId,
            String requestId,
            List<PatrolExecutionEntity.Answer> answers,
            String remark) {}

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
                || !Objects.equals(record.getRemark(), submission.remark()))
            return Mono.error(new BusinessException("error.patrol.execution.idempotency_conflict"));
        return Mono.just(record);
    }

    private Mono<PatrolExecutionEntity> write(
            PatrolTaskEntity task, Authentication auth, String id, Submission submission) {
        Instant now = Instant.now();
        return checkEligibility(task, auth.getUser().getId(), now)
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
                                                                        new BusinessException(
                                                                                "error.patrol.execution.concurrent"))));
    }

    private Mono<Void> checkEligibility(PatrolTaskEntity task, String userId, Instant now) {
        return Mono.zip(
                        plans.findById(task.getPlanId()),
                        points.findById(task.getPointId()),
                        posts.findById(task.getPostId()))
                .switchIfEmpty(
                        Mono.error(new BusinessException("error.patrol.reference.unavailable")))
                .flatMap(
                        data -> {
                            if (!data.getT1().isEnabled()
                                    || !data.getT2().isEnabled()
                                    || !data.getT3().isEnabled()) {
                                return Mono.error(
                                        new BusinessException(
                                                "error.patrol.reference.unavailable"));
                            }
                            return checkRoster(
                                    task, data.getT3(), data.getT1().getScopeKey(), userId, now);
                        });
    }

    private Mono<Void> checkRoster(
            PatrolTaskEntity task,
            PatrolPostEntity post,
            String scopeKey,
            String userId,
            Instant now) {
        // 按应用+逻辑日期判定是否配置排班；夜班归属开始日，不按当前自然日误判。
        return rosters.createQuery()
                .where(PatrolRosterEntity::getScopeKey, scopeKey)
                .and(PatrolRosterEntity::getBusinessDate, LocalDate.parse(task.getBusinessDate()))
                .and(PatrolRosterEntity::isEnabled, true)
                .fetch()
                .take(1001)
                .collectList()
                .flatMap(rows -> applyExecutionPolicy(task, post, rows, userId, now));
    }

    private Mono<Void> applyExecutionPolicy(
            PatrolTaskEntity task,
            PatrolPostEntity post,
            List<PatrolRosterEntity> rows,
            String userId,
            Instant now) {
        if (rows.size() > 1000)
            return Mono.error(new BusinessException("error.patrol.roster.too_many"));
        boolean inShift =
                rows.stream()
                        .anyMatch(
                                row ->
                                        task.getPostId().equals(row.getPostId())
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
                    post.getUserIds().contains(userId),
                    task.getRosterPolicy(),
                    !rows.isEmpty(),
                    inShift);
            return Mono.empty();
        } catch (IllegalArgumentException error) {
            return Mono.error(new BusinessException(error.getMessage()));
        }
    }

    private PatrolExecutionEntity buildRecord(
            PatrolTaskEntity task,
            Authentication auth,
            String id,
            Submission submission,
            Instant now) {
        Map<String, String> answers = new HashMap<>();
        Set<String> expected = new HashSet<>();
        for (var item : task.getStandardSnapshot()) expected.add(item.id());
        for (var answer : submission.answers()) {
            if (!expected.contains(answer.itemId())
                    || answers.put(answer.itemId(), answer.value()) != null)
                throw new BusinessException("error.patrol.execution.answers");
        }
        for (var item : task.getStandardSnapshot()) {
            String value = answers.get(item.id());
            if (item.required() && value == null
                    || value != null && !item.options().contains(value))
                throw new BusinessException("error.patrol.execution.answers");
        }
        var record = new PatrolExecutionEntity();
        record.setId(id);
        record.setTaskId(task.getId());
        record.setPlanId(task.getPlanId());
        record.setPointId(task.getPointId());
        record.setPointName(task.getPointName());
        record.setUserId(auth.getUser().getId());
        record.setUserName(auth.getUser().getName());
        record.setRequestId(submission.requestId());
        record.setReceiveTime(now.toEpochMilli());
        record.setRemark(submission.remark());
        record.setAnswers(List.copyOf(submission.answers()));
        record.setStandardSnapshot(task.getStandardSnapshot());
        return record;
    }
}
