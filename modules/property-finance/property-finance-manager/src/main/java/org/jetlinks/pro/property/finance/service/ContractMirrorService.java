package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;

import com.alibaba.fastjson.JSON;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

import org.apache.commons.codec.digest.DigestUtils;
import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.*;
import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.crud.query.QueryHelper;
import org.jetlinks.core.trace.MonoTracer;
import org.jetlinks.pro.assets.*;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.base.archive.PropertySpaceInfo;
import org.jetlinks.pro.property.finance.contract.*;
import org.jetlinks.pro.property.finance.entity.*;
import org.jetlinks.pro.tenant.holder.TenantHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import reactor.core.publisher.*;

import java.math.*;
import java.time.*;
import java.util.*;

/** 项目级合同增量镜像；预期版本覆盖整批，不按增量缺失删除，不重写应收或资金。 */
@Service
@RequiredArgsConstructor
public class ContractMirrorService {
    public static final String RESOURCE = "property-finance-contracts";
    private final FinanceScope scope;
    private final ObjectMapper mapper;
    private final ContractSpaceSource source;
    private final DocumentQueryScope visibility;
    private final ReactiveRepository<ContractMirrorEntity, String> rows;
    private final ReactiveRepository<ContractSyncStateEntity, String> states;
    private final ReactiveRepository<ContractSyncLogEntity, String> logs;

    /** 下载前读取当前版本；-1表示尚未应用任何批次，不代表获取了后台特权。 */
    public Mono<Long> version(String project) {
        return authorize(project, "sync", CrudAssetPermission.save)
                .then(tenant())
                .flatMap(
                        t ->
                                states.findById(key(t, project))
                                        .map(ContractSyncStateEntity::getVersion)
                                        .defaultIfEmpty(-1L));
    }

    public Mono<PagerResult<ContractMirrorView>> query(
            String project, String keyword, int page, int size) {
        return Mono.defer(
                        () -> {
                            if (page < 0
                                    || size < 1
                                    || size > 200
                                    || keyword != null && keyword.length() > 200)
                                return Mono.error(error("contract_query_invalid", 400));
                            return authorize(project, "query", CrudAssetPermission.read)
                                    .then(tenant())
                                    .flatMap(
                                            t -> {
                                                var q = new QueryParamEntity();
                                                q.setPageIndex(page);
                                                q.setPageSize(size);
                                                q.toQuery()
                                                        .and("tenantId", t)
                                                        .and("projectId", project)
                                                        .and("deleted", false);
                                                if (keyword != null && !keyword.isBlank())
                                                    q.toQuery()
                                                            .nest()
                                                            .like("code", keyword.trim())
                                                            .or()
                                                            .like("name", keyword.trim())
                                                            .end();
                                                q.orderBy("code");
                                                q.orderBy("id");
                                                return visibility
                                                        .inject(q, rows, CrudAssetPermission.read)
                                                        .flatMap(
                                                                secured ->
                                                                        QueryHelper.queryPager(
                                                                                secured,
                                                                                rows::createQuery))
                                                        .map(
                                                                result ->
                                                                        PagerResult.of(
                                                                                (int)
                                                                                        result
                                                                                                .getTotal(),
                                                                                result
                                                                                        .getData()
                                                                                        .stream()
                                                                                        .map(
                                                                                                ContractMirrorService
                                                                                                        ::view)
                                                                                        .toList(),
                                                                                q));
                                            });
                        })
                .as(MonoTracer.create("/property/finance/contracts/query"));
    }

    @Transactional
    public Mono<ContractSyncResult> apply(
            String project, Long expectedVersion, List<ContractMirrorInput> input) {
        return Mono.defer(
                        () -> {
                            var data = validate(project, input);
                            return authorize(project, "sync", CrudAssetPermission.save)
                                    .flatMap(
                                            auth ->
                                                    tenant().flatMap(
                                                                    tenant ->
                                                                            applyBatch(
                                                                                    tenant,
                                                                                    project,
                                                                                    expectedVersion,
                                                                                    data,
                                                                                    auth)));
                        })
                .onErrorMap(this::duplicate)
                .as(MonoTracer.create("/property/finance/contracts/apply"));
    }

    private Mono<ContractSyncResult> applyBatch(
            String tenant,
            String project,
            Long expectedVersion,
            List<ContractMirrorInput> data,
            Authentication auth) {
        return state(tenant, project, expectedVersion, auth)
                .flatMap(
                        state -> {
                            // 删除增量不能因为基础空间已失效而丢掉历史；有效合同仍须校验真实末端。
                            Mono<List<PropertySpaceInfo>> spaces =
                                    data.stream().allMatch(ContractMirrorInput::deleted)
                                            ? Mono.just(List.of())
                                            : source.spaces(project);
                            return spaces.flatMap(known -> prepare(tenant, project, data, known))
                                    .flatMap(changes -> write(state, changes, auth));
                        });
    }

    private Mono<Authentication> authorize(
            String project, String action, CrudAssetPermission permission) {
        return scope.authenticated()
                .flatMap(
                        auth -> {
                            if (!auth.hasPermission(RESOURCE, action))
                                return Mono.error(error("permission_denied", 403));
                            return scope.project(project, permission).thenReturn(auth);
                        });
    }

    private Mono<String> tenant() {
        return TenantHolder.currentId().defaultIfEmpty("");
    }

    private Mono<ContractSyncStateEntity> state(
            String tenant, String project, Long expected, Authentication auth) {
        var q = new QueryParamEntity();
        q.setForUpdate(true);
        return states.createQuery()
                .setParam(q)
                .where("id", key(tenant, project))
                .fetchOne()
                .flatMap(
                        row ->
                                Objects.equals(row.getVersion(), expected)
                                        ? Mono.just(row)
                                        : Mono.error(error("contract_sync_conflict", 409)))
                .switchIfEmpty(
                        Mono.defer(
                                () -> {
                                    if (expected != null && expected != -1)
                                        return Mono.error(error("contract_sync_conflict", 409));
                                    var row = new ContractSyncStateEntity();
                                    row.setId(key(tenant, project));
                                    row.setTenantId(tenant);
                                    row.setProjectId(project);
                                    stamp(row, auth, 0L);
                                    return states.insert(row).thenReturn(row);
                                }));
    }

    private Mono<List<Change>> prepare(
            String tenant,
            String project,
            List<ContractMirrorInput> inputs,
            List<PropertySpaceInfo> spaces) {
        var byId = new HashMap<String, PropertySpaceInfo>();
        spaces.forEach(space -> byId.put(space.id(), space));
        return Flux.fromIterable(inputs)
                .sort(Comparator.comparing(ContractMirrorInput::sourceId))
                .concatMap(input -> prepareOne(tenant, project, input, byId))
                .collectList();
    }

    private Mono<Change> prepareOne(
            String tenant,
            String project,
            ContractMirrorInput input,
            Map<String, PropertySpaceInfo> spaces) {
        return rows.findById(key(tenant, project, input.sourceId()))
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .flatMap(
                        existing -> {
                            var previous = existing.orElse(null);
                            if (previous != null && !previous.getCode().equals(input.code()))
                                return Mono.error(error("contract_identity_conflict", 409));
                            var ids = new HashSet<String>();
                            ids.add(input.spaceId());
                            if (previous != null) ids.add(previous.getSpaceId());
                            // 新旧房屋都需要写权限，不能借移动合同越过来源空间的授权边界。
                            return AssetsHolder.currentReactive()
                                    .switchIfEmpty(Mono.error(error("permission_denied", 403)))
                                    .flatMap(
                                            holder ->
                                                    holder.assertPermission(
                                                            "space", ids, CrudAssetPermission.save))
                                    .then(
                                            Mono.defer(
                                                    () ->
                                                            prepareChange(
                                                                    tenant, project, previous,
                                                                    input, spaces)));
                        });
    }

    private Mono<Change> prepareChange(
            String tenant,
            String project,
            ContractMirrorEntity previous,
            ContractMirrorInput input,
            Map<String, PropertySpaceInfo> spaces) {
        var canonical = canonical(previous, input, spaces.get(input.spaceId()));
        return rows.createQuery()
                .where("tenantId", tenant)
                .and("projectId", project)
                .and("code", input.code())
                .fetchOne()
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .map(
                        sameCode -> {
                            if (sameCode.isPresent()
                                    && !sameCode.get().getSourceId().equals(input.sourceId()))
                                throw error("contract_identity_conflict", 409);
                            // 使用平台Jackson的record支持，避免不同快照被旧序列化器全部编码成空对象。
                            var fingerprint =
                                    DigestUtils.sha256Hex(mapper.valueToTree(canonical).toString());
                            return new Change(previous, canonical, fingerprint);
                        });
    }

    private ContractMirrorInput canonical(
            ContractMirrorEntity previous, ContractMirrorInput input, PropertySpaceInfo location) {
        if (!input.deleted() && (location == null || !Boolean.TRUE.equals(location.leaf())))
            throw error("contract_space_invalid", 409);
        String fullName = location == null ? input.fullName() : location.fullName();
        if (input.deleted()) {
            // 首次拉取也可能只收到删除事实；保留来源身份，以空间标识兜底而不要求空间仍存在。
            fullName = previous == null ? input.fullName() : previous.getFullName();
            if (fullName == null || fullName.isBlank()) fullName = input.spaceId();
        }
        if (fullName == null || fullName.isBlank() || fullName.length() > 2000)
            throw error("contract_snapshot_invalid", 400);
        return new ContractMirrorInput(
                input.sourceId(),
                input.code(),
                input.name(),
                input.projectId(),
                input.spaceId(),
                fullName,
                input.customerId(),
                input.customerName(),
                input.startDate(),
                input.endDate(),
                input.leaseArea(),
                input.deleted());
    }

    private Mono<ContractSyncResult> write(
            ContractSyncStateEntity state, List<Change> changes, Authentication auth) {
        int added = 0, updated = 0, deleted = 0, unchanged = 0;
        for (var c : changes) {
            if (c.previous() != null && c.previous().getFingerprint().equals(c.fingerprint()))
                unchanged++;
            else if (c.input().deleted()) deleted++;
            else if (c.previous() == null) added++;
            else updated++;
        }
        boolean changed = added + updated + deleted > 0;
        long next = state.getVersion() + (changed ? 1 : 0);
        var result = new ContractSyncResult(next, added, updated, deleted, unchanged);
        if (!changed) return Mono.just(result);
        return Flux.fromIterable(changes)
                .filter(
                        c ->
                                c.previous() == null
                                        || !c.previous().getFingerprint().equals(c.fingerprint()))
                .concatMap(
                        c -> {
                            var row = entity(state, c, auth);
                            if (c.previous() == null) return rows.insert(row);
                            var update = rows.createUpdate().set(row);
                            // ORM 的 set(entity) 跳过 null，恢复合同必须显式清除原删除时间。
                            if (!row.getDeleted()) update.setNull("deletedTime");
                            return update.where("id", row.getId())
                                    .and("version", c.previous().getVersion())
                                    .execute()
                                    .flatMap(
                                            count ->
                                                    count == 1
                                                            ? Mono.just(count)
                                                            : Mono.error(
                                                                    error(
                                                                            "contract_sync_conflict",
                                                                            409)));
                        })
                .then(
                        states.createUpdate()
                                .set("version", next)
                                .set("modifyTime", System.currentTimeMillis())
                                .set("modifierId", auth.getUser().getId())
                                .set("modifierName", auth.getUser().getUsername())
                                .where("id", state.getId())
                                .and("version", state.getVersion())
                                .execute())
                .flatMap(
                        count -> {
                            if (count != 1) return Mono.error(error("contract_sync_conflict", 409));
                            var log = new ContractSyncLogEntity();
                            log.setId(key(state.getId(), Long.toString(next)));
                            log.setTenantId(state.getTenantId());
                            log.setProjectId(state.getProjectId());
                            log.setAdded(result.added());
                            log.setUpdated(result.updated());
                            log.setRemoved(result.deleted());
                            log.setUnchanged(result.unchanged());
                            stamp(log, auth, next);
                            return logs.insert(log).thenReturn(result);
                        });
    }

    private ContractMirrorEntity entity(
            ContractSyncStateEntity state, Change change, Authentication auth) {
        var in = change.input();
        var row = new ContractMirrorEntity();
        row.setId(key(state.getTenantId(), state.getProjectId(), in.sourceId()));
        row.setTenantId(state.getTenantId());
        row.setProjectId(state.getProjectId());
        row.setSourceId(in.sourceId());
        row.setCode(in.code());
        row.setCodeKey(key(state.getTenantId(), state.getProjectId(), in.code()));
        row.setName(in.name());
        row.setSpaceId(in.spaceId());
        row.setFullName(in.fullName());
        row.setCustomerId(in.customerId());
        row.setCustomerName(in.customerName());
        row.setStartDate(in.startDate());
        row.setEndDate(in.endDate());
        row.setLeaseArea(in.leaseArea());
        row.setFingerprint(change.fingerprint());
        stamp(row, auth, change.previous() == null ? 0L : change.previous().getVersion() + 1);
        if (change.previous() != null) row.setCreateTime(change.previous().getCreateTime());
        row.setDeleted(in.deleted());
        row.setDeletedTime(in.deleted() ? System.currentTimeMillis() : null);
        return row;
    }

    private static void stamp(FinanceEntity row, Authentication auth, long version) {
        long now = System.currentTimeMillis();
        row.setVersion(version);
        row.setDeleted(false);
        row.setCreateTime(now);
        row.setModifyTime(now);
        row.setModifierId(auth.getUser().getId());
        row.setModifierName(auth.getUser().getUsername());
    }

    static String key(String... parts) {
        return DigestUtils.sha256Hex(JSON.toJSONString(parts));
    }

    static List<ContractMirrorInput> validate(String project, List<ContractMirrorInput> inputs) {
        if (project == null
                || project.isBlank()
                || project.length() > 64
                || inputs == null
                || inputs.size() > 1000) throw error("contract_snapshot_invalid", 400);
        var ids = new HashSet<String>();
        var codes = new HashSet<String>();
        var result = new ArrayList<ContractMirrorInput>();
        for (var in : inputs) {
            if (in == null || !project.equals(in.projectId()))
                throw error("contract_snapshot_invalid", 400);
            String id = text(in.sourceId(), 128),
                    code = text(in.code(), 128),
                    name = text(in.name(), 255),
                    space = text(in.spaceId(), 64),
                    customer = text(in.customerId(), 1000);
            if (!ids.add(id) || !codes.add(code)) throw error("contract_identity_conflict", 409);
            try {
                var start = LocalDate.parse(in.startDate());
                var end = LocalDate.parse(in.endDate());
                if (start.isAfter(end) || start.getYear() < 1900 || end.getYear() > 9999)
                    throw new IllegalArgumentException();
                var area = new BigDecimal(in.leaseArea()).setScale(2, RoundingMode.UNNECESSARY);
                if (area.signum() < 0 || area.precision() > 20)
                    throw new IllegalArgumentException();
                result.add(
                        new ContractMirrorInput(
                                id,
                                code,
                                name,
                                project,
                                space,
                                in.fullName(),
                                customer,
                                in.customerName() == null || in.customerName().isBlank()
                                        ? customer
                                        : text(in.customerName(), 1000),
                                start.toString(),
                                end.toString(),
                                area.toPlainString(),
                                in.deleted()));
            } catch (Exception failure) {
                throw error("contract_snapshot_invalid", 400);
            }
        }
        return result;
    }

    private static String text(String value, int max) {
        if (value == null || value.isBlank() || value.trim().length() > max)
            throw error("contract_snapshot_invalid", 400);
        return value.trim();
    }

    private Throwable duplicate(Throwable failure) {
        for (var cause = failure; cause != null; cause = cause.getCause())
            if (cause instanceof org.springframework.dao.DuplicateKeyException
                    || cause
                            instanceof org.hswebframework.ezorm.rdb.exception.DuplicateKeyException)
                return error("contract_sync_conflict", 409);
        return failure;
    }

    static ContractMirrorView view(ContractMirrorEntity r) {
        return new ContractMirrorView(
                r.getId(),
                r.getSourceId(),
                r.getProjectId(),
                r.getCode(),
                r.getName(),
                r.getSpaceId(),
                r.getFullName(),
                r.getCustomerId(),
                r.getCustomerName(),
                r.getStartDate(),
                r.getEndDate(),
                r.getLeaseArea(),
                r.getVersion());
    }

    private record Change(
            ContractMirrorEntity previous, ContractMirrorInput input, String fingerprint) {}
}
