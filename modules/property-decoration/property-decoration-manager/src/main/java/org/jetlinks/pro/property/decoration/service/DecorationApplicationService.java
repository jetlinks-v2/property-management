package org.jetlinks.pro.property.decoration.service;

import lombok.RequiredArgsConstructor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.crud.query.QueryHelper;
import org.jetlinks.core.trace.MonoTracer;
import org.jetlinks.pro.property.base.house.PropertyHouseholdInfo;
import org.jetlinks.pro.property.decoration.entity.*;
import org.jetlinks.pro.property.decoration.model.DecorationModels;
import org.jetlinks.pro.property.decoration.model.DecorationRequests;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;
import java.util.List;
import java.util.Objects;
import java.util.ArrayList;
import java.util.UUID;
import java.util.function.BiFunction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import static org.jetlinks.pro.property.decoration.service.DecorationRules.*;

/**
 * 装修聚合唯一写入边界：同房占用唯一约束、行锁、版本条件及动作轨迹在同一响应式事务内。
 * 数据权限由 scope 注入；办理资格区分基础员工与认证本人有效房屋关系。
 */
@Service
@RequiredArgsConstructor
public class DecorationApplicationService {
    private final DecorationScope scope;
    private final DecorationApplicationRepository repository;
    private final DecorationHistoryRepository histories;
    private final DecorationConfigurationService configurations;
    private final DecorationAttachmentService attachments;
    private final DecorationSelfInspectionService selfInspections;
    private final ObjectMapper json;
    private final org.springframework.transaction.ReactiveTransactionManager transactions;

    public Mono<PagerResult<DecorationApplicationEntity>> query(QueryParamEntity input, boolean mine) {
        return scope.current().flatMap(ctx -> scoped(input, ctx, mine)).flatMap(q -> QueryHelper.queryPager(q, repository::createQuery));
    }
    private Mono<QueryParamEntity> scoped(QueryParamEntity input, DecorationScope.Context ctx, boolean mine) {
        return scope.query(input, ctx).map(q -> q.toNestQuery(n -> {
            n.and("deleted", false); if (mine) n.and("ownerUserId", ctx.userId());
        }).getParam());
    }
    public Mono<DecorationApplicationEntity> read(String id, boolean mine) {
        return scope.current().flatMap(ctx -> load(id, ctx, mine, false));
    }
    private Mono<DecorationApplicationEntity> load(String id, DecorationScope.Context ctx, boolean mine, boolean lock) {
        return scoped(QueryParamEntity.newQuery().where("id", id).getParam(), ctx, mine)
            .doOnNext(q -> q.setForUpdate(lock)).flatMap(q -> repository.createQuery().setParam(q).fetchOne())
            .switchIfEmpty(Mono.error(error("not_found", 404)));
    }
    /** 超时后只读核对原请求，不触发第二次办理。 */
    public Mono<DecorationApplicationEntity> creationResult(String requestId, boolean mine) {
        return scope.current().flatMap(ctx -> load(identity(ctx.applicationId() + ":" + ctx.userId() + ":" + requestId), ctx, mine, false))
            .filter(row -> row.getCreatorId().equals(row.getOwnerUserId()) || !mine);
    }
    public Mono<DecorationApplicationEntity> actionResult(String id, String requestId, boolean mine) {
        return read(id, mine).flatMap(row -> scope.current().flatMap(ctx -> histories.findById(identity(id + ":" + requestId))
            .filter(h -> h.getActorId().equals(ctx.userId())).map(h -> row)))
            .switchIfEmpty(Mono.error(error("request_not_found", 404)));
    }
    public Mono<List<DecorationApplicationEntity>> export(QueryParamEntity input) {
        return scope.current().flatMap(ctx -> scoped(input, ctx, false)).flatMap(q -> repository.createQuery().setParam(q).count()
            .doOnNext(count -> require(count <= 10000, "export_limit"))
            .then(Mono.defer(() -> { q.setPaging(true); q.setPageIndex(0); q.setPageSize(10000); return repository.createQuery().setParam(q).fetch().collectList(); })));
    }
    public Flux<DecorationHistoryEntity> history(String id, boolean mine) {
        return read(id, mine).flatMapMany(row -> histories.createQuery().where("decorationId", id)
            .orderBy(org.hswebframework.ezorm.rdb.operator.dml.query.SortOrder.asc("version")).fetch());
    }
    public Mono<DecorationApplicationEntity> create(DecorationRequests.Create input, boolean mine) {
        require(input.getStartTime() <= input.getCompletionTime(), "invalid_dates");
        return scope.current().flatMap(ctx -> (mine ? Mono.empty() : scope.employee().then())
            .then(scope.household(input.getProjectId(), input.getSpaceId(), input.getCustomerId(), mine))
            .flatMap(home -> createForHome(input, mine, ctx, home)))
            .as(org.springframework.transaction.reactive.TransactionalOperator.create(transactions)::transactional)
            .onErrorResume(e -> e instanceof org.hswebframework.ezorm.rdb.exception.DuplicateKeyException || e instanceof org.springframework.dao.DuplicateKeyException,
                e -> recoverCreation(input, mine))
            .as(MonoTracer.create("/property/decoration/create"));
    }
    /** 唯一键冲突发生后先完成回滚，再识别同请求竞争与同房竞争。 */
    private Mono<DecorationApplicationEntity> recoverCreation(DecorationRequests.Create input, boolean mine) {
        return scope.current().flatMap(ctx -> repository.findById(identity(ctx.applicationId() + ":" + ctx.userId() + ":" + input.getRequestId()))
            .filter(row -> row.getTenantId().equals(ctx.tenantId()) && row.getCreatorId().equals(ctx.userId()))
            .doOnNext(row -> require(row.getRequestDigest().equals(digest(input)) && row.getSource().equals(mine ? "h5_owner" : "pc_admin"), "request_conflict")))
            .switchIfEmpty(Mono.error(error("house_occupied", 409)));
    }
    private Mono<DecorationApplicationEntity> createForHome(DecorationRequests.Create input, boolean mine, DecorationScope.Context ctx, PropertyHouseholdInfo home) {
        require(home.userId() != null && !home.userId().isBlank(), "owner_account_required");
        String id = identity(ctx.applicationId() + ":" + ctx.userId() + ":" + input.getRequestId());
        String digest = digest(input);
        return repository.findById(id).flatMap(old -> {
            require(old.getCreatorId().equals(ctx.userId()) && old.getTenantId().equals(ctx.tenantId()) && old.getRequestDigest().equals(digest)
                && old.getSource().equals(mine ? "h5_owner" : "pc_admin"), "request_conflict");
            return Mono.just(old);
        }).switchIfEmpty(Mono.defer(() -> Mono.zip(
            configurations.match(ctx, input.getProjectId(), "access", "", "", ""),
            configurations.match(ctx, input.getProjectId(), "agreement", home.houseType(), "", ""),
            configurations.match(ctx, input.getProjectId(), "materials", home.houseType(), input.getDecorationType(), ""),
            scope.project(input.getProjectId()))
            .flatMap(tuple -> {
                require(tuple.getT1().getContent().getEligibleNatures().contains(home.nature()), "household_mismatch");
                var row = new DecorationApplicationEntity(); row.setId(id); row.setApplicationId(ctx.applicationId()); row.setTenantId(ctx.tenantId());
                row.setProjectId(input.getProjectId()); row.setProjectName(tuple.getT4().getName()); row.setSpaceId(home.spaceId()); row.setSpaceName(home.spaceName());
                row.setCustomerId(home.customerId()); row.setOwnerUserId(home.userId()); row.setOwnerName(home.customerName()); row.setContactPhone(input.getContactPhone()); row.setRelationshipId(home.relationshipId());
                row.setCreatorId(ctx.userId()); row.setCreatorName(ctx.userName()); row.setSource(mine ? "h5_owner" : "pc_admin"); row.setRequestDigest(digest);
                row.setNumber("ZX-" + DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneId.of("Asia/Shanghai")).format(Instant.now()) + "-" + id.substring(0,8).toUpperCase(java.util.Locale.ROOT));
                row.setActiveHouseKey(identity(ctx.tenantId() + ":" + home.spaceId())); row.setVersion(1L); row.setCreateTime(System.currentTimeMillis()); row.setModifyTime(row.getCreateTime());
                row.setConstructionUnit(input.getConstructionUnit()); row.setConstructionManager(input.getConstructionManager()); row.setManagerPhone(input.getManagerPhone());
                row.setHouseType(home.houseType()); row.setDecorationType(input.getDecorationType()); row.setStartTime(input.getStartTime()); row.setCompletionTime(input.getCompletionTime());
                row.setStatus("reviewing"); row.setDeleted(false); row.setDepositReviewStatus(0); row.setPaymentStatus(0); row.setRefundStatus(0); row.setAcceptanceRound(0); row.setAcceptanceType(0);
                var details = new DecorationModels.ApplicationDetails();
                details.setOwnerCertificate(input.getOwnerCertificate()); details.setConstructionUnit(input.getConstructionUnit()); details.setConstructionManager(input.getConstructionManager());
                details.setManagerPhone(input.getManagerPhone()); details.setManagerCertificate(input.getManagerCertificate()); details.setConstructionType(input.getConstructionType());
                details.setDecorationType(input.getDecorationType()); details.setHouseType(home.houseType()); details.setStartTime(input.getStartTime()); details.setCompletionTime(input.getCompletionTime());
                details.setNoticesAccepted(input.isNoticesAccepted()); details.setSelfInspectionStatus(input.getSelfInspectionStatus()); details.setSelfInspectionRemark(input.getSelfInspectionRemark()); details.setSelfInspectionFiles(input.getSelfInspectionFiles()); details.setSignatureFiles(input.getSignatureFiles());
                details.setAgreement(tuple.getT2().getContent().getAgreement());
                details.setMaterials(materials(tuple.getT3().getContent().getMaterials(), input.getMaterials())); row.setDetails(details);
                var ids = new ArrayList<>(files(row));
                return attachments.validate(ids, row.getProjectId(), null, ctx, false).then(repository.insert(row))
                    .then(attachments.consume(ids, id, ctx)).then(selfInspections.capture(row))
                    .then(writeHistory(row, ctx, "create", input.getRequestId(), digest, null, null)).thenReturn(row);
            })))
            ;
    }
    private List<DecorationModels.Material> materials(List<DecorationModels.MaterialDefinition> definitions, List<DecorationRequests.MaterialUpload> uploads) {
        require(uploads.stream().map(DecorationRequests.MaterialUpload::getItemId).distinct().count() == uploads.size(), "invalid_materials");
        for (var upload : uploads) require(definitions.stream().anyMatch(d -> d.isEnabled() && d.getId().equals(upload.getItemId())), "invalid_materials");
        return definitions.stream().filter(DecorationModels.MaterialDefinition::isEnabled).map(definition -> {
            var material = new DecorationModels.Material(); material.setDefinition(definition);
            var upload = uploads.stream().filter(u -> definition.getId().equals(u.getItemId())).findFirst().orElse(null);
            require(!definition.isRequired() || upload != null && !upload.getFileIds().isEmpty(), "required_material");
            if (upload != null) material.setFileIds(upload.getFileIds());
            return material;
        }).toList();
    }
    /** 动作幂等记录与聚合行锁共用事务，超时重试同键只读已提交结果，参数变化拒绝。 */
    @Transactional
    public Mono<DecorationApplicationEntity> mutate(String id, String action, DecorationRequests.Action input, boolean mine,
            BiFunction<DecorationApplicationEntity, DecorationScope.Context, Mono<Void>> operation) {
        return scope.current().flatMap(ctx -> (mine ? Mono.empty() : scope.employee().then())
            .then(load(id, ctx, mine, true)).flatMap(row -> (mine ? owner(row, ctx) : Mono.empty()).then(
                histories.findById(identity(id + ":" + input.getRequestId())).flatMap(history -> {
                    require(history.getActorId().equals(ctx.userId()) && history.getAction().equals(action) && history.getRequestDigest().equals(digest(input)), "request_conflict");
                    return Mono.just(row);
                }).switchIfEmpty(Mono.defer(() -> {
                    require(Objects.equals(row.getVersion(), input.getVersion()), "version_conflict");
                    String before = row.getStatus();
                    return operation.apply(row, ctx).then(Mono.defer(() -> commit(row, ctx, action, input, before)));
                })))))
            .as(MonoTracer.create("/property/decoration/action"));
    }
    private Mono<Void> owner(DecorationApplicationEntity row, DecorationScope.Context ctx) {
        require(row.getOwnerUserId().equals(ctx.userId()), "not_owner");
        return scope.household(row.getProjectId(), row.getSpaceId(), row.getCustomerId(), true).then();
    }
    private Mono<DecorationApplicationEntity> commit(DecorationApplicationEntity row, DecorationScope.Context ctx, String action, DecorationRequests.Action input, String before) {
        long version = row.getVersion(); row.setVersion(version + 1); row.setModifyTime(System.currentTimeMillis());
        var update = repository.createUpdate().set(row);
        if (row.getActiveHouseKey() == null) update.setNull("activeHouseKey");
        if (row.getInspectionPlan() != null && row.getNextInspectionTime() == null) update.setNull("nextInspectionTime");
        return update.where("id", row.getId()).and("version", version).execute().doOnNext(n -> require(n == 1, "version_conflict"))
            .then(writeHistory(row, ctx, action, input.getRequestId(), digest(input), before, input.getRemark())).thenReturn(row);
    }
    private Mono<Void> writeHistory(DecorationApplicationEntity row, DecorationScope.Context ctx, String action, String request, String digest, String before, String remark) {
        var history = new DecorationHistoryEntity(); history.setId(identity(row.getId() + ":" + request)); history.setRequestKey(history.getId());
        history.setDecorationId(row.getId()); history.setRequestDigest(digest); history.setAction(action); history.setActorId(ctx.userId()); history.setActorName(ctx.userName());
        history.setSnapshot(row); history.setBeforeStatus(before); history.setAfterStatus(row.getStatus()); history.setRemark(remark); history.setVersion(row.getVersion()); history.setCreateTime(System.currentTimeMillis());
        return histories.insert(history).then();
    }
    public static List<String> files(DecorationApplicationEntity row) {
        var files = new ArrayList<>(row.getDetails().getSelfInspectionFiles()); files.addAll(row.getDetails().getSignatureFiles());
        row.getDetails().getMaterials().forEach(m -> { files.addAll(m.getFileIds()); if (m.getAnnotatedFileId() != null) files.add(m.getAnnotatedFileId()); });
        return files;
    }
    public static String identity(String seed) { return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString(); }
    private String digest(Object input) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(input))); }
        catch (java.io.IOException | java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
