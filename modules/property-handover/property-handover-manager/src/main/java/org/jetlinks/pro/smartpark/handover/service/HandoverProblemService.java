package org.jetlinks.pro.smartpark.handover.service;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.authorization.exception.AccessDenyException;
import org.hswebframework.web.exception.NotFoundException;
import org.hswebframework.web.id.IDGenerator;
import org.jetlinks.core.trace.MonoTracer;
import org.jetlinks.pro.assets.AssetType;
import org.jetlinks.pro.assets.AssetsHolder;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.smartpark.handover.api.*;
import org.jetlinks.pro.smartpark.handover.entity.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.DigestUtils;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Three-source problem lifecycle; status, photos and audit nodes share a database transaction. */
@Service
@RequiredArgsConstructor
public class HandoverProblemService {
    private final HandoverScope scope;
    private final HandoverDirectory directory;
    private final HandoverConfigurationService configuration;
    private final HandoverFiles files;
    private final HandoverProcessService processes;
    private final HandoverProblemRepository problems;
    private final HandoverPhotoRepository photos;
    private final HandoverNodeRepository nodes;
    private final HandoverItemRepository items;
    private final HandoverResponsibilityRepository responsibilities;
    private final ObjectMapper mapper;
    private final org.springframework.transaction.ReactiveTransactionManager transactions;
    private final org.jetlinks.pro.smartpark.handover.asset.HandoverProblemAccess assetAccess;

    public Mono<QueryParamEntity> selector(HandoverQuery input) {
        return assetAccess.selector(input);
    }

    public Mono<PagerResult<HandoverProblemEntity>> query(HandoverQuery input) {
        return selector(input).flatMap(problems::queryPager);
    }

    public Mono<Map<String, Long>> counts(HandoverQuery input) {
        return selector(input)
                .flatMap(
                        q -> {
                            var counts =
                                    Flux.range(0, 5)
                                            .concatMap(
                                                    status ->
                                                            problems.createQuery()
                                                                    .setParam(
                                                                            q.clone()
                                                                                    .toNestQuery()
                                                                                    .and(
                                                                                            "status",
                                                                                            status)
                                                                                    .getParam())
                                                                    .count()
                                                                    .map(
                                                                            n ->
                                                                                    Map.entry(
                                                                                            status,
                                                                                            n
                                                                                                    .longValue())))
                                            .collectMap(Map.Entry::getKey, Map.Entry::getValue);
                            return counts.map(
                                    c -> {
                                        Map<String, Long> result = new LinkedHashMap<>();
                                        String[] names = {
                                            "pending",
                                            "processing",
                                            "review",
                                            "completed",
                                            "rejected"
                                        };
                                        long total = 0;
                                        for (int i = 0; i < names.length; i++) {
                                            long count = c.getOrDefault(i, 0L);
                                            result.put(names[i], count);
                                            total += count;
                                        }
                                        result.put("total", total);
                                        return result;
                                    });
                        });
    }

    private Mono<HandoverProblemEntity> visible(String id) {
        return scope.current()
                .flatMap(
                        c ->
                                problems.findById(id)
                                        .switchIfEmpty(Mono.error(new NotFoundException()))
                                        .doOnNext(p -> scope.owned(p, c)))
                .flatMap(
                        p -> {
                            HandoverQuery q = new HandoverQuery();
                            q.setProjectId(p.getProjectId());
                            q.setIds(List.of(id));
                            return selector(q)
                                    .flatMap(
                                            query ->
                                                    problems.createQuery()
                                                            .setParam(query)
                                                            .fetchOne())
                                    .switchIfEmpty(Mono.error(new AccessDenyException()));
                        });
    }

    public Mono<HandoverProblemEntity> read(String id) {
        return visible(id).flatMap(this::details);
    }

    public Mono<HandoverProblemEntity> details(HandoverProblemEntity problem) {
        return Mono.zip(
                        photos.createQuery()
                                .setParam(
                                        QueryParamEntity.newQuery()
                                                .where("problemId", problem.getId())
                                                .and("deleted", false)
                                                .orderByAsc("sortIndex")
                                                .orderByAsc("createTime")
                                                .getParam())
                                .fetch()
                                .take(1001)
                                .collectList(),
                        nodes.createQuery()
                                .setParam(
                                        QueryParamEntity.newQuery()
                                                .where("problemId", problem.getId())
                                                .and("deleted", false)
                                                .orderByAsc("createTime")
                                                .orderByAsc("version")
                                                .getParam())
                                .fetch()
                                .take(5001)
                                .collectList())
                .map(
                        t -> {
                            HandoverRules.require(
                                    t.getT1().size() <= 1000 && t.getT2().size() <= 5000,
                                    "history_limit");
                            problem.setPhotos(
                                    t.getT1().stream()
                                            .map(
                                                    photo -> {
                                                        FileRef f = new FileRef();
                                                        f.setFileId(photo.getFileId());
                                                        f.setName(photo.getName());
                                                        f.setType(photo.getType());
                                                        f.setSize(photo.getSize());
                                                        f.setPhotoType(photo.getPhotoType());
                                                        f.setSortIndex(photo.getSortIndex());
                                                        return f;
                                                    })
                                            .toList());
                            problem.setNodes(t.getT2());
                            return problem;
                        });
    }

    /** Full intake preflight, reused by Excel before any business row is inserted. */
    public Mono<ProblemRequest> validate(ProblemRequest request) {
        HandoverRules.require(
                HandoverRules.SOURCES.contains(Objects.toString(request.getSource(), "")),
                "source_invalid");
        HandoverRules.require(
                HandoverRules.hasText(request.getDescription())
                        && request.getDescription().length() <= 4000,
                "description_required");
        HandoverRules.require(
                request.getClientRequestId() == null
                        || request.getClientRequestId().length() <= 128,
                "request_invalid");
        HandoverRules.require(
                request.getInspectionType() == null
                        || Set.of("management", "handover").contains(request.getInspectionType()),
                "inspection_type_invalid");
        HandoverRules.require(
                request.getStatus() == null
                        || (request.getStatus() >= 0 && request.getStatus() <= 4),
                "state_invalid");
        Mono<Void> check =
                directory
                        .employee()
                        .then(scope.project(request.getProjectId(), CrudAssetPermission.save))
                        .then(directory.project(request.getProjectId()))
                        .then()
                        .then(
                                configuration.category(
                                        request.getProjectId(), request.getCategoryId()))
                        .then(files.validate(request.getPhotos()));
        if (Integer.valueOf(3).equals(request.getStatus()))
            check =
                    check.then(
                            org.hswebframework.web.authorization.Authentication.currentReactive()
                                    .doOnNext(
                                            auth -> {
                                                if (!auth.hasPermission(
                                                        "handover-problem", "final-review"))
                                                    throw new AccessDenyException();
                                            })
                                    .then());
        if (HandoverRules.hasText(request.getOrganizationId()))
            check =
                    check.then(
                                    configuration.responsibility(
                                            request.getProjectId(), request.getOrganizationId()))
                            .then();
        if (HandoverRules.hasText(request.getSpaceId()))
            check =
                    check.then(directory.space(request.getProjectId(), request.getSpaceId()))
                            .then();
        if (HandoverRules.hasText(request.getInitiatorId()))
            check = check.then(directory.user(request.getInitiatorId())).then();
        if (!"SITE_INSPECTION".equals(request.getSource())) {
            HandoverRules.require(HandoverRules.hasText(request.getItemId()), "item_required");
            check =
                    check.then(
                                    configuration
                                            .read(items, request.getItemId())
                                            .doOnNext(
                                                    item -> {
                                                        HandoverRules.require(
                                                                Objects.equals(
                                                                                item.getProjectId(),
                                                                                request
                                                                                        .getProjectId())
                                                                        && Objects.equals(
                                                                                item.getSource(),
                                                                                request
                                                                                        .getSource()),
                                                                "item_invalid");
                                                        HandoverRules.require(
                                                                !Boolean.TRUE.equals(
                                                                        item.getConfirmed()),
                                                                "item_confirmed");
                                                    }))
                            .then();
        } else HandoverRules.require(!HandoverRules.hasText(request.getItemId()), "item_invalid");
        String stage =
                "SITE_INSPECTION".equals(request.getSource())
                        ? "site"
                        : HandoverConfigurationService.stage(request.getSource());
        return check.then(processes.assertAvailable(request.getProjectId(), stage))
                .thenReturn(request);
    }

    public Mono<HandoverProblemEntity> create(ProblemRequest request) {
        return scope.current()
                .flatMap(context -> createOrReplay(request, context))
                .as(MonoTracer.create("/property/handover/problem/create"));
    }

    private Mono<HandoverProblemEntity> createOrReplay(
            ProblemRequest request, HandoverContext context) {
        String id =
                HandoverRules.hasText(request.getClientRequestId())
                        ? requestId(context, request.getClientRequestId())
                        : IDGenerator.MD5.generate();
        String hash = hash(request);
        var atomic =
                org.springframework.transaction.reactive.TransactionalOperator.create(transactions);
        // 主键包含租户/应用/用户/提交ID。并发失败后在已回滚事务外读取赢家，
        // 相同内容返回同一结果；不同内容冲突。逻辑删除仍保留幂等墓碑。
        return replay(id, hash, context)
                .switchIfEmpty(
                        Mono.defer(
                                () ->
                                        atomic.transactional(
                                                validate(request)
                                                        .then(
                                                                Mono.defer(
                                                                        () ->
                                                                                createNew(
                                                                                        request,
                                                                                        context, id,
                                                                                        hash))))))
                .onErrorResume(error -> replay(id, hash, context).switchIfEmpty(Mono.error(error)));
    }

    private Mono<HandoverProblemEntity> replay(String id, String hash, HandoverContext context) {
        return problems.findById(id)
                .flatMap(
                        existing -> {
                            scope.owned(existing, context);
                            HandoverRules.conflict(Objects.equals(existing.getRequestHash(), hash));
                            return read(existing.getId());
                        });
    }

    private Mono<HandoverProblemEntity> createNew(
            ProblemRequest request, HandoverContext c, String id, String hash) {
        HandoverProblemEntity problem = new HandoverProblemEntity();
        scope.initialize(problem, c);
        problem.setId(id);
        problem.setProjectId(request.getProjectId());
        problem.setSource(request.getSource());
        problem.setItemId(request.getItemId());
        problem.setClientRequestId(request.getClientRequestId());
        problem.setRequestHash(hash);
        problem.setSourceReportId(request.getSourceReportId());
        problem.setStatus(request.getStatus() == null ? 0 : request.getStatus());
        problem.setAuditStage(problem.getStatus() == 3 ? 2 : 0);
        Mono<Void> itemLock = Mono.empty();
        if (HandoverRules.hasText(request.getItemId()))
            itemLock =
                    configuration
                            .read(items, request.getItemId())
                            .flatMap(
                                    item ->
                                            items.createUpdate()
                                                    .set("version", item.getVersion() + 1)
                                                    .where("id", item.getId())
                                                    .and("version", item.getVersion())
                                                    .and("confirmed", false)
                                                    .and("deleted", false)
                                                    .execute())
                            .doOnNext(n -> HandoverRules.conflict(n == 1))
                            .then();
        String stage =
                "SITE_INSPECTION".equals(request.getSource())
                        ? "site"
                        : HandoverConfigurationService.stage(request.getSource());
        return populate(problem, request, c)
                .then(processes.writeStage(request.getProjectId(), stage))
                .then(itemLock)
                .then(problems.insert(problem))
                .then(addPhotos(problem, request.getPhotos(), null, c))
                .then(addNode(problem, "create", null, request.getDescription(), c))
                .then(details(problem));
    }

    private Mono<Void> populate(
            HandoverProblemEntity p, ProblemRequest request, HandoverContext c) {
        p.setTitle(request.getTitle());
        p.setDescription(request.getDescription());
        p.setLocation(request.getLocation());
        p.setSpaceId(request.getSpaceId());
        p.setSpaceName(null);
        p.setCategoryId(request.getCategoryId());
        p.setCategoryName(null);
        p.setOrganizationId(request.getOrganizationId());
        p.setOrganizationName(null);
        p.setInspectionType(request.getInspectionType());
        p.setInspectionTime(
                request.getInspectionTime() == null
                        ? (p.getInspectionTime() == null
                                ? System.currentTimeMillis()
                                : p.getInspectionTime())
                        : request.getInspectionTime());
        p.setInitiatorId(
                HandoverRules.hasText(request.getInitiatorId())
                        ? request.getInitiatorId()
                        : (p.getInitiatorId() == null ? c.userId() : p.getInitiatorId()));
        Mono<Void> metadata =
                directory
                        .user(p.getInitiatorId())
                        .doOnNext(u -> p.setInitiatorName(u.getName()))
                        .then();
        if (HandoverRules.hasText(request.getSpaceId()))
            metadata =
                    metadata.then(
                                    directory
                                            .space(p.getProjectId(), request.getSpaceId())
                                            .doOnNext(s -> p.setSpaceName(s.getName())))
                            .then();
        if (HandoverRules.hasText(request.getCategoryId()))
            metadata =
                    metadata.then(
                                    configuration
                                            .readCategory(request.getCategoryId())
                                            .doOnNext(
                                                    category ->
                                                            p.setCategoryName(category.getName())))
                            .then();
        if (HandoverRules.hasText(request.getOrganizationId()))
            metadata =
                    metadata.then(
                                    configuration
                                            .responsibility(
                                                    p.getProjectId(), request.getOrganizationId())
                                            .doOnNext(
                                                    r -> {
                                                        p.setOrganizationId(r.getOrganizationId());
                                                        p.setOrganizationName(r.getName());
                                                    }))
                            .then();
        return metadata;
    }

    @Transactional
    public Mono<HandoverProblemEntity> edit(String id, ProblemRequest request) {
        return directory
                .employee()
                .then(scope.current())
                .flatMap(
                        c ->
                                visible(id)
                                        .flatMap(
                                                p -> {
                                                    HandoverRules.require(
                                                            p.getStatus() == 0
                                                                    || p.getStatus() == 4,
                                                            "state_invalid");
                                                    HandoverRules.require(
                                                            request.getStatus() == null
                                                                    || Objects.equals(
                                                                            request.getStatus(),
                                                                            p.getStatus()),
                                                            "state_invalid");
                                                    HandoverRules.conflict(
                                                            Objects.equals(
                                                                    p.getVersion(),
                                                                    request.getVersion()));
                                                    HandoverRules.require(
                                                            Objects.equals(
                                                                            p.getProjectId(),
                                                                            request.getProjectId())
                                                                    && Objects.equals(
                                                                            p.getSource(),
                                                                            request.getSource())
                                                                    && Objects.equals(
                                                                            p.getItemId(),
                                                                            request.getItemId()),
                                                            "problem_identity_changed");
                                                    return validate(request)
                                                            .then(populate(p, request, c))
                                                            .then(
                                                                    processes.writeStage(
                                                                            p.getProjectId(),
                                                                            "SITE_INSPECTION"
                                                                                            .equals(
                                                                                                    p
                                                                                                            .getSource())
                                                                                    ? "site"
                                                                                    : HandoverConfigurationService
                                                                                            .stage(
                                                                                                    p
                                                                                                            .getSource())))
                                                            .then(update(p, request.getVersion()))
                                                            .then(
                                                                    addPhotos(
                                                                            p,
                                                                            request.getPhotos(),
                                                                            null,
                                                                            c))
                                                            .then(
                                                                    addNode(
                                                                            p,
                                                                            "edit",
                                                                            p.getStatus(),
                                                                            request
                                                                                    .getDescription(),
                                                                            c))
                                                            .then(details(p));
                                                }));
    }

    @Transactional
    public Mono<HandoverProblemEntity> action(String id, String action, ActionRequest request) {
        return scope.current()
                .flatMap(
                        c ->
                                visible(id)
                                        .flatMap(
                                                p ->
                                                        prepare(p, action, request)
                                                                .then(
                                                                        apply(
                                                                                p, action, request,
                                                                                c))));
    }

    private Mono<Void> prepare(HandoverProblemEntity p, String action, ActionRequest request) {
        HandoverRules.conflict(Objects.equals(p.getVersion(), request.getVersion()));
        HandoverRules.next(p.getStatus(), p.getAuditStage(), action, request);
        Mono<Void> allowed;
        if (Set.of("accept", "rectify").contains(action)) allowed = contractor(p);
        else
            allowed =
                    directory
                            .employee()
                            .then(scope.project(p.getProjectId(), CrudAssetPermission.save));
        if ("assign".equals(action))
            allowed =
                    allowed.then(
                                    configuration.responsibility(
                                            p.getProjectId(), request.getOrganizationId()))
                            .then();
        return allowed.then(files.validate(request.getPhotos()));
    }

    private Mono<Void> contractor(HandoverProblemEntity p) {
        if (!HandoverRules.hasText(p.getOrganizationId()))
            return Mono.error(new AccessDenyException());
        return directory
                .directOrganizations()
                .doOnNext(
                        ids -> {
                            if (!ids.contains(p.getOrganizationId()))
                                throw new AccessDenyException();
                        })
                .then(configuration.responsibility(p.getProjectId(), p.getOrganizationId()))
                .then(
                        AssetsHolder.assertPermission(
                                AssetType.of("organization"),
                                p.getOrganizationId(),
                                CrudAssetPermission.save))
                .then();
    }

    private Mono<HandoverProblemEntity> apply(
            HandoverProblemEntity p, String action, ActionRequest request, HandoverContext c) {
        int before = p.getStatus();
        int[] next = HandoverRules.next(before, p.getAuditStage(), action, request);
        p.setStatus(next[0]);
        p.setAuditStage(next[1]);
        Mono<Void> assign =
                "assign".equals(action)
                        ? configuration
                                .responsibility(p.getProjectId(), request.getOrganizationId())
                                .doOnNext(
                                        r -> {
                                            p.setOrganizationId(r.getOrganizationId());
                                            p.setOrganizationName(r.getName());
                                        })
                                .then()
                        : Mono.empty();
        String description =
                Boolean.FALSE.equals(request.getPassed())
                        ? request.getReason()
                        : request.getDescription();
        return assign.then(update(p, request.getVersion()))
                .then(
                        addPhotos(
                                p,
                                request.getPhotos(),
                                "rectify".equals(action) ? "rectification" : "problem",
                                c))
                .then(addNode(p, action, before, description, c))
                .then(details(p));
    }

    private Mono<Void> update(HandoverProblemEntity problem, int expected) {
        problem.setVersion(expected + 1);
        problem.setModifyTime(System.currentTimeMillis());
        var update = problems.createUpdate().set(problem);
        // DTO中的null代表明确清空；EasyORM set(entity)默认跳过null，必须同步清理ID与快照名。
        if (problem.getCategoryId() == null) update.setNull("categoryId").setNull("categoryName");
        if (problem.getSpaceId() == null) update.setNull("spaceId").setNull("spaceName");
        if (problem.getOrganizationId() == null)
            update.setNull("organizationId").setNull("organizationName");
        if (problem.getLocation() == null) update.setNull("location");
        if (problem.getTitle() == null) update.setNull("title");
        if (problem.getInspectionType() == null) update.setNull("inspectionType");
        return update.where("id", problem.getId())
                .and("version", expected)
                .and("deleted", false)
                .execute()
                .doOnNext(count -> HandoverRules.conflict(count == 1))
                .then();
    }

    @Transactional
    public Mono<List<HandoverProblemEntity>> finalReview(BatchReviewRequest request) {
        HandoverRules.require(
                request.getItems() != null
                        && !request.getItems().isEmpty()
                        && request.getItems().size() <= 200,
                "batch_limit");
        HandoverRules.require(
                request.getItems().stream().map(BatchReviewRequest.Item::getId).distinct().count()
                        == request.getItems().size(),
                "batch_duplicate");
        return scope.current()
                .flatMap(
                        c ->
                                directory
                                        .employee()
                                        .thenMany(
                                                Flux.fromIterable(request.getItems())
                                                        .concatMap(
                                                                input ->
                                                                        visible(input.getId())
                                                                                .flatMap(
                                                                                        p -> {
                                                                                            HandoverRules
                                                                                                    .require(
                                                                                                            Objects
                                                                                                                    .equals(
                                                                                                                            p
                                                                                                                                    .getProjectId(),
                                                                                                                            request
                                                                                                                                    .getProjectId()),
                                                                                                            "project_invalid");
                                                                                            ActionRequest
                                                                                                    action =
                                                                                                            batchAction(
                                                                                                                    request,
                                                                                                                    input
                                                                                                                            .getVersion());
                                                                                            return prepare(
                                                                                                            p,
                                                                                                            "final-review",
                                                                                                            action)
                                                                                                    .thenReturn(
                                                                                                            p);
                                                                                        })))
                                        .collectList()
                                        .flatMap(
                                                all ->
                                                        Flux.fromIterable(all)
                                                                .concatMap(
                                                                        p ->
                                                                                apply(
                                                                                        p,
                                                                                        "final-review",
                                                                                        batchAction(
                                                                                                request,
                                                                                                p
                                                                                                        .getVersion()),
                                                                                        c))
                                                                .collectList()));
    }

    private static ActionRequest batchAction(BatchReviewRequest request, Integer version) {
        ActionRequest a = new ActionRequest();
        a.setVersion(version);
        a.setPassed(request.getPassed());
        a.setReason(request.getReason());
        return a;
    }

    @Transactional
    public Mono<Void> delete(String id, Integer version) {
        return directory
                .employee()
                .then(scope.current())
                .flatMap(
                        c ->
                                visible(id)
                                        .flatMap(
                                                p -> {
                                                    HandoverRules.conflict(
                                                            Objects.equals(
                                                                    p.getVersion(), version));
                                                    return scope.project(
                                                                    p.getProjectId(),
                                                                    CrudAssetPermission.delete)
                                                            .then(
                                                                    problems.createUpdate()
                                                                            .set("deleted", true)
                                                                            .set(
                                                                                    "version",
                                                                                    version + 1)
                                                                            .where("id", id)
                                                                            .and("version", version)
                                                                            .and("deleted", false)
                                                                            .execute())
                                                            .doOnNext(
                                                                    n ->
                                                                            HandoverRules.conflict(
                                                                                    n == 1))
                                                            .then(
                                                                    addNode(
                                                                            p,
                                                                            "delete",
                                                                            p.getStatus(),
                                                                            null,
                                                                            c));
                                                }));
    }

    private Mono<Void> addPhotos(
            HandoverProblemEntity problem, List<FileRef> refs, String type, HandoverContext c) {
        return Flux.fromIterable(refs == null ? List.<FileRef>of() : refs)
                .concatMap(
                        ref -> {
                            String effectiveType =
                                    type == null
                                            ? (ref.getPhotoType() == null
                                                    ? "problem"
                                                    : ref.getPhotoType())
                                            : type;
                            String id =
                                    DigestUtils.md5DigestAsHex(
                                            (problem.getId()
                                                            + "|"
                                                            + effectiveType
                                                            + "|"
                                                            + ref.getFileId())
                                                    .getBytes(StandardCharsets.UTF_8));
                            return photos.findById(id)
                                    .hasElement()
                                    .flatMap(
                                            exists -> {
                                                if (exists) return Mono.empty();
                                                HandoverPhotoEntity photo =
                                                        new HandoverPhotoEntity();
                                                scope.initialize(photo, c);
                                                photo.setId(id);
                                                photo.setProjectId(problem.getProjectId());
                                                photo.setProblemId(problem.getId());
                                                photo.setFileId(ref.getFileId());
                                                photo.setName(ref.getName());
                                                photo.setSize(ref.getSize());
                                                photo.setType(ref.getType());
                                                photo.setPhotoType(effectiveType);
                                                photo.setSortIndex(
                                                        ref.getSortIndex() == null
                                                                ? 0
                                                                : ref.getSortIndex());
                                                return photos.insert(photo).then();
                                            });
                        })
                .then();
    }

    private Mono<Void> addNode(
            HandoverProblemEntity problem,
            String action,
            Integer before,
            String description,
            HandoverContext c) {
        HandoverNodeEntity node = new HandoverNodeEntity();
        scope.initialize(node, c);
        node.setId(IDGenerator.MD5.generate());
        node.setProjectId(problem.getProjectId());
        node.setProblemId(problem.getId());
        node.setVersion(problem.getVersion());
        node.setAction(action);
        node.setOperatorId(c.userId());
        node.setOperatorName(c.userName());
        node.setTime(System.currentTimeMillis());
        node.setDescription(description);
        node.setBeforeStatus(before);
        node.setAfterStatus(problem.getStatus());
        return nodes.insert(node).then();
    }

    private static String requestId(HandoverContext c, String request) {
        return DigestUtils.md5DigestAsHex(
                (c.tenantId() + "|" + c.applicationId() + "|" + c.userId() + "|" + request)
                        .getBytes(StandardCharsets.UTF_8));
    }

    private String hash(ProblemRequest request) {
        return DigestUtils.md5DigestAsHex(
                mapper.valueToTree(request).toString().getBytes(StandardCharsets.UTF_8));
    }
}
