package org.jetlinks.pro.smartpark.handover.service;

import lombok.RequiredArgsConstructor;

import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.id.IDGenerator;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.base.organization.PropertyOrganizationInfo;
import org.jetlinks.pro.smartpark.handover.entity.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 八阶段独立推进；启动会议解锁后续，总结只保留原有三项前置。 */
@Service
@RequiredArgsConstructor
public class HandoverProcessService {
    private final HandoverScope scope;
    private final HandoverDirectory directory;
    private final HandoverProcessRepository processes;
    private final HandoverRecordRepository records;
    private final HandoverItemRepository items;
    private final HandoverProblemRepository problems;
    private final HandoverStageLogRepository logs;

    static String id(HandoverContext c, String projectId) {
        String key = c.tenantId() + "|" + c.applicationId() + "|" + projectId;
        return org.springframework.util.DigestUtils.md5DigestAsHex(
                key.getBytes(StandardCharsets.UTF_8));
    }

    public Mono<HandoverProcessEntity> get(String projectId) {
        return scope.current().flatMap(context -> readProject(context, projectId));
    }

    private Mono<HandoverProcessEntity> readProject(HandoverContext context, String projectId) {
        return scope.project(projectId, CrudAssetPermission.read)
                .then(directory.project(projectId))
                .flatMap(
                        project ->
                                processes
                                        .findById(id(context, projectId))
                                        .doOnNext(row -> scope.owned(row, context))
                                        .switchIfEmpty(
                                                Mono.fromSupplier(
                                                        () -> initial(context, project))));
    }

    private HandoverProcessEntity initial(
            HandoverContext context, PropertyOrganizationInfo project) {
        HandoverProcessEntity process = new HandoverProcessEntity();
        process.setId(id(context, project.getId()));
        process.setProjectId(project.getId());
        process.setName(project.getName());
        scope.initialize(process, context);
        List<HandoverProcessEntity.StageState> stages = new ArrayList<>();
        for (String code : HandoverRules.STAGES) {
            var state = new HandoverProcessEntity.StageState();
            state.setCode(code);
            state.setStatus("pending");
            state.setVersion(0);
            stages.add(state);
        }
        process.setStages(stages);
        return process;
    }

    public Mono<PagerResult<HandoverProcessEntity>> query(HandoverQuery input) {
        int size = Math.max(1, Math.min(200, input.getPageSize()));
        int page = Math.max(0, input.getPageIndex());
        // 公共目录已完成资产筛选。仅对项目目录分页，不把问题跨来源分页退化为内存合并。
        var projects = directory.projects().filter(p -> matches(p, input));
        return projects.index()
                .skip((long) page * size)
                .take(size)
                .concatMap(tuple -> get(tuple.getT2().getId()))
                .collectList()
                .zipWith(directory.projects().filter(p -> matches(p, input)).count())
                .map(
                        result ->
                                PagerResult.of(
                                        result.getT2().intValue(),
                                        result.getT1(),
                                        input.parameter("name")));
    }

    private static boolean matches(PropertyOrganizationInfo project, HandoverQuery input) {
        return (!HandoverRules.hasText(input.getProjectId())
                        || project.getId().equals(input.getProjectId()))
                && (!HandoverRules.hasText(input.getKeyword())
                        || project.getName().contains(input.getKeyword()));
    }

    public Mono<Void> assertAvailable(String projectId, String stage) {
        return get(projectId)
                .doOnNext(
                        process -> {
                            if (!"kickoff".equals(stage)) {
                                HandoverRules.require(
                                        !"pending".equals(state(process, "kickoff").getStatus()),
                                        "kickoff_required");
                            }
                            HandoverRules.require(
                                    !"completed".equals(state(process, stage).getStatus()),
                                    "state_invalid");
                        })
                .then();
    }

    /** 业务写入与阶段完结竞争同一流程版本，禁止完结期间插入新记录或新问题。 */
    @Transactional
    public Mono<Void> writeStage(String projectId, String stage) {
        return assertAvailable(projectId, stage)
                .then(scope.current())
                .flatMap(
                        context ->
                                get(projectId)
                                        .flatMap(
                                                process -> {
                                                    var selected = state(process, stage);
                                                    if ("pending".equals(selected.getStatus()))
                                                        return transition(
                                                                        process,
                                                                        stage,
                                                                        "start",
                                                                        process.getVersion(),
                                                                        context)
                                                                .then();
                                                    return processes
                                                            .createUpdate()
                                                            .set(
                                                                    "version",
                                                                    process.getVersion() + 1)
                                                            .set(
                                                                    "modifyTime",
                                                                    System.currentTimeMillis())
                                                            .where("id", process.getId())
                                                            .and("version", process.getVersion())
                                                            .and("deleted", false)
                                                            .execute()
                                                            .doOnNext(
                                                                    count ->
                                                                            HandoverRules.conflict(
                                                                                    count == 1))
                                                            .then();
                                                }));
    }

    @Transactional
    public Mono<HandoverProcessEntity> action(
            String projectId, String stage, String action, Integer version) {
        HandoverRules.require(HandoverRules.STAGES.contains(stage), "stage_invalid");
        HandoverRules.require(
                java.util.Set.of("start", "complete").contains(action), "action_invalid");
        return directory
                .employee()
                .then(scope.project(projectId, CrudAssetPermission.save))
                .then(scope.current())
                .flatMap(
                        context ->
                                get(projectId)
                                        .flatMap(
                                                process ->
                                                        transition(
                                                                process, stage, action, version,
                                                                context)));
    }

    private Mono<HandoverProcessEntity> transition(
            HandoverProcessEntity process,
            String stage,
            String action,
            Integer version,
            HandoverContext context) {
        HandoverRules.conflict(Objects.equals(version, process.getVersion()));
        var selected = state(process, stage);
        if (!"kickoff".equals(stage)) {
            HandoverRules.require(
                    !"pending".equals(state(process, "kickoff").getStatus()), "kickoff_required");
        }
        HandoverRules.require(
                ("start".equals(action) ? "pending" : "active").equals(selected.getStatus()),
                "state_invalid");
        return completionGuard(process, stage, action)
                .then(processes.findById(process.getId()).hasElement())
                .flatMap(
                        exists ->
                                (exists ? Mono.empty() : processes.insert(process))
                                        .then(
                                                Mono.defer(
                                                        () ->
                                                                advance(
                                                                        process, selected, action,
                                                                        version, context))));
    }

    private Mono<HandoverProcessEntity> advance(
            HandoverProcessEntity process,
            HandoverProcessEntity.StageState selected,
            String action,
            int version,
            HandoverContext context) {
        long now = System.currentTimeMillis();
        selected.setStatus("start".equals(action) ? "active" : "completed");
        selected.setVersion(selected.getVersion() + 1);
        if ("start".equals(action)) selected.setStartTime(now);
        else selected.setEndTime(now);
        process.setVersion(version + 1);
        process.setModifyTime(now);
        HandoverStageLogEntity log = stageLog(process, selected.getCode(), action, context);
        return processes
                .createUpdate()
                .set("stages", process.getStages())
                .set("version", version + 1)
                .set("modifyTime", now)
                .where("id", process.getId())
                .and("version", version)
                .and("deleted", false)
                .execute()
                .doOnNext(count -> HandoverRules.conflict(count == 1))
                .then(logs.insert(log))
                .thenReturn(process);
    }

    private HandoverStageLogEntity stageLog(
            HandoverProcessEntity process, String stage, String action, HandoverContext context) {
        HandoverStageLogEntity log = new HandoverStageLogEntity();
        scope.initialize(log, context);
        log.setId(IDGenerator.MD5.generate());
        log.setProjectId(process.getProjectId());
        log.setStage(stage);
        log.setAction(action);
        log.setOperatorId(context.userId());
        log.setOperatorName(context.userName());
        log.setTime(System.currentTimeMillis());
        return log;
    }

    private QueryParamEntity projectQuery(HandoverProcessEntity process) {
        return QueryParamEntity.newQuery()
                .where("tenantId", process.getTenantId())
                .and("applicationId", process.getApplicationId())
                .and("projectId", process.getProjectId())
                .and("deleted", false)
                .getParam();
    }

    private Mono<Void> completionGuard(HandoverProcessEntity process, String stage, String action) {
        if (!"complete".equals(action)) return Mono.empty();
        if ("summary".equals(stage)) {
            for (String prerequisite : List.of("kickoff", "site", "material")) {
                HandoverRules.require(
                        "completed".equals(state(process, prerequisite).getStatus()),
                        "summary_prerequisite");
            }
        }
        QueryParamEntity query = projectQuery(process);
        Mono<Void> recordCheck = Mono.empty();
        if (java.util.Set.of("kickoff", "material", "disclosure", "summary").contains(stage)) {
            QueryParamEntity recordQuery =
                    query.clone().toNestQuery().and("stage", stage).getParam();
            recordCheck =
                    records.createQuery()
                            .setParam(recordQuery)
                            .count()
                            .doOnNext(
                                    count ->
                                            HandoverRules.require(
                                                    count > 0, "stage_record_required"))
                            .then();
        }
        if ("physical".equals(stage) || "quality".equals(stage) || "material".equals(stage)) {
            String source =
                    "physical".equals(stage)
                            ? "PHYSICAL_HANDOVER"
                            : "quality".equals(stage) ? "QUALITY_ACCEPTANCE" : "MATERIAL_TRANSFER";
            query.toQuery().and("source", source).and("confirmed", false);
            return recordCheck
                    .then(items.createQuery().setParam(query).count())
                    .doOnNext(count -> HandoverRules.require(count == 0, "stage_items_pending"))
                    .then();
        }
        if (java.util.Set.of("kickoff", "disclosure", "summary").contains(stage))
            return recordCheck;
        if ("rectification".equals(stage)) {
            query.toQuery().and("status", "not", 3);
            return problems.createQuery()
                    .setParam(query)
                    .count()
                    .doOnNext(count -> HandoverRules.require(count == 0, "stage_problems_pending"))
                    .then();
        }
        return Mono.empty();
    }

    static HandoverProcessEntity.StageState state(HandoverProcessEntity process, String stage) {
        return process.getStages().stream()
                .filter(s -> stage.equals(s.getCode()))
                .findFirst()
                .orElseThrow();
    }
}
