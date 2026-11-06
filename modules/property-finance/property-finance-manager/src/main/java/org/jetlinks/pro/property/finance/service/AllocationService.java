package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;
import static org.jetlinks.pro.property.finance.service.InvoiceCompanyService.*;

import com.alibaba.fastjson.JSON;

import lombok.RequiredArgsConstructor;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.*;
import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.crud.query.QueryHelper;
import org.jetlinks.core.trace.MonoTracer;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.finance.document.AllocationInput;
import org.jetlinks.pro.property.finance.entity.*;
import org.jetlinks.pro.property.finance.payment.PaymentRules;
import org.jetlinks.pro.tenant.holder.TenantHolder;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import reactor.core.publisher.*;

import java.math.BigDecimal;
import java.util.*;

/** 票据分配的唯一写入口。号码唯一键保护跨票本重号，票本行锁保护单本状态，修改和审计同事务。 */
@Service
@RequiredArgsConstructor
public class AllocationService {
    private final FinanceScope scope;
    private final AllocationRecipientSource recipients;
    private final RecordedDictionarySource dictionaries;
    private final ReactiveRepository<AllocationBookEntity, String> books;
    private final ReactiveRepository<AllocationNumberEntity, String> numbers;
    private final ReactiveRepository<AllocationLogEntity, String> logs;
    private static final List<String> EDITABLE_FIELDS =
            List.of(
                    "buyDate",
                    "recipientId",
                    "recipientUsername",
                    "recipientName",
                    "billType",
                    "billTypeName",
                    "billCode",
                    "billSource",
                    "billSourceName",
                    "quota",
                    "startNumber",
                    "endNumber",
                    "numberCount",
                    "discardedCount",
                    "enabled",
                    "recovered",
                    "remark");

    public record NumberState(Long version, Boolean enabled, String reason) {}

    public record Options(Map<String, String> types, Map<String, String> sources) {}

    private record Actor(Authentication auth, String tenant) {}

    private record Prepared(AllocationInput input, AllocationBookEntity row) {}

    public Mono<Options> options(String project) {
        return scope.project(project, CrudAssetPermission.read)
                .then(
                        Mono.zip(
                                dictionaries.options("bill_type"),
                                dictionaries.options("bill_info_source")))
                .map(t -> new Options(t.getT1(), t.getT2()));
    }

    public Mono<PagerResult<AllocationRecipientSource.Recipient>> recipients(
            String project, int pageIndex, int pageSize, String keywords) {
        return scope.project(project, CrudAssetPermission.save)
                .then(recipients.options(pageIndex, pageSize, keywords));
    }

    public Mono<PagerResult<AllocationBookEntity>> query(String project, QueryParamEntity input) {
        return scope.project(project, CrudAssetPermission.read)
                .then(tenant())
                .flatMap(
                        tenant -> {
                            var query = page(input);
                            query.toNestQuery(
                                    n ->
                                            n.and("tenantId", tenant)
                                                    .and("projectId", project)
                                                    .and("deleted", false));
                            return QueryHelper.queryPager(query, books::createQuery);
                        });
    }

    public Mono<AllocationBookEntity> detail(String project, String id) {
        return scope.project(project, CrudAssetPermission.read)
                .then(tenant())
                .flatMap(tenant -> book(project, id, tenant, false));
    }

    public Mono<PagerResult<AllocationNumberEntity>> pool(
            String project, String id, QueryParamEntity input) {
        return detail(project, id)
                .flatMap(
                        book -> {
                            var query = page(input);
                            if (input == null || input.getSorts().isEmpty()) {
                                query.getSorts().clear();
                                query.orderBy("number");
                            }
                            query.toNestQuery(n -> n.and("bookId", id).and("deleted", false));
                            return QueryHelper.queryPager(query, numbers::createQuery);
                        });
    }

    public Mono<PagerResult<AllocationLogEntity>> logs(
            String project, String id, QueryParamEntity input) {
        return scope.project(project, CrudAssetPermission.read)
                .then(tenant())
                .flatMap(
                        tenant -> {
                            var query = page(input);
                            query.toNestQuery(
                                    n ->
                                            n.and("tenantId", tenant)
                                                    .and("projectId", project)
                                                    .and("bookId", id));
                            return QueryHelper.queryPager(query, logs::createQuery);
                        });
    }

    @Transactional
    public Mono<List<AllocationBookEntity>> create(String project, List<AllocationInput> input) {
        return authorized(project, CrudAssetPermission.save)
                .flatMap(actor -> createBatch(project, input, actor))
                .onErrorMap(AllocationService::duplicate)
                .as(MonoTracer.create("property.finance.allocation.create"));
    }

    private Mono<List<AllocationBookEntity>> createBatch(
            String project, List<AllocationInput> input, Actor actor) {
        return Mono.defer(
                () -> {
                    validateBatch(input);
                    String batch = UUID.randomUUID().toString();
                    // 所有来源先完成预检；后续唯一键或审计失败，整个批次在同一事务回滚。
                    return Flux.fromIterable(input)
                            .concatMap(i -> prepare(i, null))
                            .collectList()
                            .flatMapMany(Flux::fromIterable)
                            .concatMap(
                                    template ->
                                            Flux.range(0, template.input().bookCount())
                                                    .concatMap(
                                                            index ->
                                                                    createBook(
                                                                            project, batch,
                                                                            template, index,
                                                                            actor)))
                            .collectList();
                });
    }

    private Mono<AllocationBookEntity> createBook(
            String project, String batch, Prepared template, int index, Actor actor) {
        var row = copy(template.row());
        init(row, actor.auth());
        row.setId(UUID.randomUUID().toString());
        row.setTenantId(actor.tenant());
        row.setProjectId(project);
        row.setBatchNumber(batch);
        row.setStartNumber(
                template.input().startNumber() + (long) index * template.input().numberCount());
        row.setEndNumber(row.getStartNumber() + row.getNumberCount() - 1);
        row.setCreatorId(actor.auth().getUser().getId());
        row.setCreatorName(actor.auth().getUser().getUsername());
        return books.insert(row)
                .then(insertNumbers(row, actor.auth()))
                .then(log(row, null, "CREATE", actor.auth()))
                .thenReturn(row);
    }

    @Transactional
    public Mono<AllocationBookEntity> update(String project, String id, AllocationInput input) {
        return authorized(project, CrudAssetPermission.save)
                .flatMap(
                        actor ->
                                book(project, id, actor.tenant(), true)
                                        .flatMap(old -> replace(old, input, actor)))
                .onErrorMap(AllocationService::duplicate)
                .as(MonoTracer.create("property.finance.allocation.update"));
    }

    private Mono<AllocationBookEntity> replace(
            AllocationBookEntity old, AllocationInput input, Actor actor) {
        validate(input, false);
        version(old.getVersion(), input.version());
        return unused(old.getId())
                .then(prepare(input, old))
                .flatMap(prepared -> writeReplacement(old, prepared.row(), actor.auth()));
    }

    private Mono<AllocationBookEntity> writeReplacement(
            AllocationBookEntity old, AllocationBookEntity next, Authentication auth) {
        init(next, auth);
        next.setId(old.getId());
        next.setTenantId(old.getTenantId());
        next.setProjectId(old.getProjectId());
        next.setBatchNumber(old.getBatchNumber());
        next.setCreatorId(old.getCreatorId());
        next.setCreatorName(old.getCreatorName());
        next.setCreateTime(old.getCreateTime());
        next.setVersion(old.getVersion() + 1);
        boolean rebuild =
                !Objects.equals(old.getBillType(), next.getBillType())
                        || !Objects.equals(old.getBillCode(), next.getBillCode())
                        || !Objects.equals(old.getStartNumber(), next.getStartNumber())
                        || !Objects.equals(old.getNumberCount(), next.getNumberCount());
        next.setDiscardedCount(rebuild ? 0 : old.getDiscardedCount());
        var update = audit(books.createUpdate(), auth, old.getVersion());
        var wrapper = new BeanWrapperImpl(next);
        for (String field : EDITABLE_FIELDS) {
            Object value = wrapper.getPropertyValue(field);
            if (value == null) update.setNull(field);
            else update.set(field, value);
        }
        // 仅号段/命名空间变化时替换号码；资料编辑不复活独立废弃号码。
        return update.where("id", old.getId())
                .and("version", old.getVersion())
                .execute()
                .flatMap(SubjectService::changed)
                .then(
                        rebuild
                                ? retireNumbers(old.getId(), auth).then(insertNumbers(next, auth))
                                : Mono.empty())
                .then(log(next, old, "UPDATE", auth))
                .thenReturn(next);
    }

    @Transactional
    public Mono<Void> delete(String project, String id, long version) {
        return authorized(project, CrudAssetPermission.delete)
                .flatMap(
                        actor ->
                                book(project, id, actor.tenant(), true)
                                        .flatMap(old -> deleteBook(old, version, actor.auth())))
                .as(MonoTracer.create("property.finance.allocation.delete"));
    }

    private Mono<Void> deleteBook(AllocationBookEntity old, long provided, Authentication auth) {
        version(old.getVersion(), provided);
        return unused(old.getId())
                .then(
                        audit(books.createUpdate(), auth, provided)
                                .set("deleted", true)
                                .set("deletedTime", System.currentTimeMillis())
                                .where("id", old.getId())
                                .and("version", provided)
                                .execute())
                .flatMap(SubjectService::changed)
                .then(retireNumbers(old.getId(), auth))
                .then(log(old, old, "DELETE", auth));
    }

    @Transactional
    public Mono<AllocationNumberEntity> numberState(
            String project, String bookId, String numberId, NumberState input) {
        return authorized(project, CrudAssetPermission.save)
                .flatMap(
                        actor ->
                                book(project, bookId, actor.tenant(), true)
                                        .flatMap(
                                                book ->
                                                        changeNumber(
                                                                book,
                                                                numberId,
                                                                input,
                                                                actor.auth())))
                .as(MonoTracer.create("property.finance.allocation.number.state"));
    }

    private Mono<AllocationNumberEntity> changeNumber(
            AllocationBookEntity book, String numberId, NumberState input, Authentication auth) {
        if (input == null
                || input.enabled() == null
                || input.reason() == null
                || input.reason().isBlank()
                || input.reason().trim().length() > 500)
            return Mono.error(error("allocation_invalid", 400));
        return numbers.createQuery()
                .where("id", numberId)
                .and("bookId", book.getId())
                .and("deleted", false)
                .fetchOne()
                .switchIfEmpty(Mono.error(error("allocation_not_found", 404)))
                .flatMap(row -> writeNumberState(book, row, input, auth));
    }

    private Mono<AllocationNumberEntity> writeNumberState(
            AllocationBookEntity book,
            AllocationNumberEntity row,
            NumberState input,
            Authentication auth) {
        version(row.getVersion(), input.version());
        if (Boolean.TRUE.equals(row.getUsed())) return Mono.error(error("allocation_used", 409));
        if (input.enabled().equals(row.getEnabled())) return Mono.just(row);
        int discarded = book.getDiscardedCount() + (input.enabled() ? -1 : 1);
        if (discarded < 0 || discarded > book.getNumberCount())
            return Mono.error(error("allocation_corrupt", 409));
        var before = new AllocationNumberEntity();
        org.springframework.beans.BeanUtils.copyProperties(row, before);
        Mono<Void> updateNumber =
                audit(numbers.createUpdate(), auth, row.getVersion())
                        .set("enabled", input.enabled())
                        .where("id", row.getId())
                        .and("version", row.getVersion())
                        .and("used", false)
                        .execute()
                        .flatMap(SubjectService::changed);
        Mono<Void> updateBook =
                audit(books.createUpdate(), auth, book.getVersion())
                        .set("discardedCount", discarded)
                        .where("id", book.getId())
                        .and("version", book.getVersion())
                        .execute()
                        .flatMap(SubjectService::changed);
        return updateNumber
                .then(updateBook)
                .then(
                        Mono.defer(
                                () -> {
                                    row.setVersion(row.getVersion() + 1);
                                    row.setEnabled(input.enabled());
                                    row.setModifyTime(System.currentTimeMillis());
                                    row.setModifierId(auth.getUser().getId());
                                    row.setModifierName(auth.getUser().getUsername());
                                    return numberLog(book, before, row, input.reason(), auth)
                                            .thenReturn(row);
                                }));
    }

    private Mono<AllocationBookEntity> book(
            String project, String id, String tenant, boolean lock) {
        var query = new QueryParamEntity();
        query.setForUpdate(lock);
        return books.createQuery()
                .setParam(query)
                .where("id", id)
                .and("tenantId", tenant)
                .and("projectId", project)
                .and("deleted", false)
                .fetchOne()
                .switchIfEmpty(Mono.error(error("allocation_not_found", 404)));
    }

    private Mono<Void> unused(String id) {
        return numbers.createQuery()
                .where("bookId", id)
                .and("deleted", false)
                .and("used", true)
                .count()
                .flatMap(
                        count ->
                                count > 0
                                        ? Mono.error(error("allocation_used", 409))
                                        : Mono.empty());
    }

    private Mono<Prepared> prepare(AllocationInput input, AllocationBookEntity old) {
        boolean sameRecipient =
                old != null && Objects.equals(input.recipientId(), old.getRecipientId());
        Mono<AllocationRecipientSource.Recipient> recipient =
                sameRecipient
                        ? Mono.just(
                                new AllocationRecipientSource.Recipient(
                                        old.getRecipientId(),
                                        old.getRecipientUsername(),
                                        old.getRecipientName()))
                        : recipients.require(input.recipientId());
        Mono<String> type =
                label(
                        "bill_type",
                        input.billType(),
                        old == null ? null : old.getBillType(),
                        old == null ? null : old.getBillTypeName());
        Mono<String> source =
                input.billSource() == null || input.billSource().isBlank()
                        ? Mono.just("")
                        : label(
                                "bill_info_source",
                                input.billSource(),
                                old == null ? null : old.getBillSource(),
                                old == null ? null : old.getBillSourceName());
        return Mono.zip(recipient, type, source)
                .map(
                        values -> {
                            var row = new AllocationBookEntity();
                            row.setBuyDate(input.buyDate());
                            row.setRecipientId(values.getT1().id());
                            row.setRecipientUsername(values.getT1().username());
                            row.setRecipientName(values.getT1().name());
                            row.setBillType(input.billType());
                            row.setBillTypeName(values.getT2());
                            row.setBillCode(input.billCode().trim());
                            row.setBillSource(
                                    input.billSource() == null || input.billSource().isBlank()
                                            ? null
                                            : input.billSource());
                            row.setBillSourceName(values.getT3().isEmpty() ? null : values.getT3());
                            row.setQuota(
                                    input.quota() == null || input.quota().isBlank()
                                            ? null
                                            : new BigDecimal(input.quota()).setScale(2));
                            row.setStartNumber(input.startNumber());
                            row.setEndNumber(input.startNumber() + input.numberCount() - 1L);
                            row.setNumberCount(input.numberCount());
                            row.setUsedCount(0);
                            row.setDiscardedCount(0);
                            row.setEnabled(input.enabled());
                            row.setRecovered(Boolean.TRUE.equals(input.recovered()));
                            row.setRemark(input.remark());
                            return new Prepared(input, row);
                        });
    }

    private Mono<String> label(String code, String value, String oldValue, String oldName) {
        if (value != null && value.equals(oldValue)) return Mono.just(oldName);
        return dictionaries
                .options(code)
                .flatMap(
                        options ->
                                options.containsKey(value)
                                        ? Mono.just(options.get(value))
                                        : Mono.error(error("allocation_dictionary_invalid", 400)));
    }

    private Mono<Void> insertNumbers(AllocationBookEntity book, Authentication auth) {
        // 一次最多2000行，不构建整批百万对象；唯一身份包含租户/项目/类型/代码/号码。
        return Flux.range(0, book.getNumberCount())
                .map(
                        index -> {
                            var row = new AllocationNumberEntity();
                            row.setId(UUID.randomUUID().toString());
                            init(row, auth);
                            row.setBookId(book.getId());
                            row.setNumber(book.getStartNumber() + index);
                            row.setActiveKey(
                                    PaymentRules.digest(
                                            JSON.toJSONString(
                                                    List.of(
                                                            book.getTenantId(),
                                                            book.getProjectId(),
                                                            book.getBillType(),
                                                            book.getBillCode(),
                                                            row.getNumber()))));
                            row.setUsed(false);
                            row.setEnabled(true);
                            return row;
                        })
                .buffer(2000)
                .concatMap(numbers::insertBatch)
                .then();
    }

    private Mono<Void> retireNumbers(String id, Authentication auth) {
        return numbers.createUpdate()
                .set("deleted", true)
                .setNull("activeKey")
                .set("deletedTime", System.currentTimeMillis())
                .set("modifierId", auth.getUser().getId())
                .set("modifierName", auth.getUser().getUsername())
                .set("modifyTime", System.currentTimeMillis())
                .where("bookId", id)
                .and("deleted", false)
                .execute()
                .then();
    }

    private Mono<Void> log(
            AllocationBookEntity row,
            AllocationBookEntity before,
            String action,
            Authentication auth) {
        var entry = baseLog(row, action, auth);
        entry.setBeforeSnapshot(before == null ? null : JSON.toJSONString(before));
        entry.setAfterSnapshot("DELETE".equals(action) ? null : JSON.toJSONString(row));
        return logs.insert(entry).then();
    }

    private Mono<Void> numberLog(
            AllocationBookEntity book,
            AllocationNumberEntity before,
            AllocationNumberEntity after,
            String reason,
            Authentication auth) {
        var entry = baseLog(book, after.getEnabled() ? "NUMBER_ENABLE" : "NUMBER_DISCARD", auth);
        entry.setNumberId(after.getId());
        entry.setReason(reason.trim());
        entry.setBeforeSnapshot(JSON.toJSONString(before));
        entry.setAfterSnapshot(JSON.toJSONString(after));
        return logs.insert(entry).then();
    }

    private AllocationLogEntity baseLog(
            AllocationBookEntity row, String action, Authentication auth) {
        var entry = new AllocationLogEntity();
        entry.setId(UUID.randomUUID().toString());
        init(entry, auth);
        entry.setTenantId(row.getTenantId());
        entry.setProjectId(row.getProjectId());
        entry.setBookId(row.getId());
        entry.setAction(action);
        return entry;
    }

    private Mono<Actor> authorized(String project, CrudAssetPermission permission) {
        return scope.project(project, permission)
                .then(scope.authenticated())
                .flatMap(auth -> tenant().map(tenant -> new Actor(auth, tenant)));
    }

    private static AllocationBookEntity copy(AllocationBookEntity row) {
        var out = new AllocationBookEntity();
        org.springframework.beans.BeanUtils.copyProperties(row, out);
        return out;
    }

    private static Mono<String> tenant() {
        return TenantHolder.currentId().defaultIfEmpty("");
    }

    private static QueryParamEntity page(QueryParamEntity input) {
        var query = SubjectService.page(input == null ? new QueryParamEntity() : input);
        query.setForUpdate(false);
        if (query.getSorts().isEmpty()) query.toQuery().orderByDesc("createTime");
        query.orderBy("id");
        return query;
    }

    private static void version(Long expected, Long provided) {
        if (!Objects.equals(expected, provided)) throw error("version_conflict", 409);
    }

    private static void validateBatch(List<AllocationInput> input) {
        if (input == null || input.isEmpty() || input.size() > 50)
            throw error("allocation_invalid", 400);
        long total = 0;
        for (var row : input) {
            validate(row, true);
            total += (long) row.bookCount() * row.numberCount();
        }
        if (total > 1000000) throw error("allocation_too_large", 400);
    }

    private static void validate(AllocationInput input, boolean create) {
        if (input == null
                || input.buyDate() == null
                || input.recipientId() == null
                || input.recipientId().isBlank()
                || input.recipientId().length() > 64
                || input.billType() == null
                || input.billType().isBlank()
                || input.billType().length() > 64
                || input.billCode() == null
                || !input.billCode().trim().matches("[A-Za-z0-9_~!@#$%^&*\\-]{1,255}")
                || input.billSource() != null && input.billSource().length() > 64
                || input.bookCount() == null
                || input.bookCount() < 1
                || input.bookCount() > 99
                || !create && input.bookCount() != 1
                || input.numberCount() == null
                || input.numberCount() < 1
                || input.numberCount() > 9999
                || input.startNumber() == null
                || input.startNumber() < 1
                || input.startNumber() > 999999999999L
                || input.startNumber() + (long) input.numberCount() * input.bookCount() - 1
                        > 999999999999L
                || input.enabled() == null
                || input.remark() != null && input.remark().length() > 255)
            throw error("allocation_invalid", 400);
        if (input.quota() != null && !input.quota().isBlank()) {
            try {
                var value = new BigDecimal(input.quota());
                if (value.signum() < 0
                        || value.scale() > 2
                        || value.precision() - value.scale() > 16)
                    throw new IllegalArgumentException();
            } catch (IllegalArgumentException failure) {
                throw error("allocation_invalid", 400);
            }
        }
    }

    private static Throwable duplicate(Throwable failure) {
        for (var cause = failure; cause != null; cause = cause.getCause())
            if (cause instanceof org.springframework.dao.DuplicateKeyException
                    || cause
                            instanceof org.hswebframework.ezorm.rdb.exception.DuplicateKeyException)
                return error("allocation_overlap", 409);
        return failure;
    }
}
