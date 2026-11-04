package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;
import static org.jetlinks.pro.property.finance.service.InvoiceCompanyService.*;

import com.alibaba.fastjson.JSON;

import lombok.RequiredArgsConstructor;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.authorization.Authentication;
import org.jetlinks.core.trace.MonoTracer;
import org.jetlinks.pro.assets.*;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.base.archive.*;
import org.jetlinks.pro.property.finance.entity.*;
import org.jetlinks.pro.property.finance.receivable.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import reactor.core.publisher.*;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.LocalDate;
import java.util.*;

/** 原账归档、两子账与日志同事务；基础关系只能通过公开命令读取及锁定。 */
@Service
@RequiredArgsConstructor
public class ReceivableSplitService {
    private final FinanceScope scope;
    private final HouseArchiveSource archive;
    private final SplitRules rules;
    private final ReactiveRepository<ReceivableEntity, String> bills;
    private final ReactiveRepository<ReceivableLogEntity, String> logs;

    public Mono<List<CustomerOption>> options(String project, String id) {
        return scope.project(project, CrudAssetPermission.save)
                .then(row(project, id, false))
                .flatMap(
                        row ->
                                archive.context(project, row.getSpaceId(), false)
                                        .map(
                                                context -> {
                                                    var options =
                                                            new LinkedHashMap<
                                                                    String, CustomerOption>();
                                                    options.put(
                                                            row.getCustomerRelationshipId(),
                                                            new CustomerOption(
                                                                    row.getCustomerRelationshipId(),
                                                                    row.getCustomerName()));
                                                    context.customers()
                                                            .forEach(
                                                                    customer ->
                                                                            options.put(
                                                                                    customer
                                                                                            .relationshipId(),
                                                                                    new CustomerOption(
                                                                                            customer
                                                                                                    .relationshipId(),
                                                                                            customer
                                                                                                    .name())));
                                                    return List.copyOf(options.values());
                                                }));
    }

    public Mono<Preview> preview(String project, String id, SplitInput input, LocalDate today) {
        return scope.project(project, CrudAssetPermission.save)
                .then(row(project, id, false))
                .flatMap(
                        row ->
                                archive.context(project, row.getSpaceId(), false)
                                        .map(
                                                context ->
                                                        prepare(row, input, context, today)
                                                                .preview()));
    }

    @Transactional
    public Mono<List<ReceivableView>> submit(
            String project, String id, Submit request, LocalDate today) {
        return scope.project(project, CrudAssetPermission.save)
                .then(scope.authenticated())
                .flatMap(
                        auth ->
                                row(project, id, false)
                                        .flatMap(
                                                initial ->
                                                        archive.context(
                                                                        project,
                                                                        initial.getSpaceId(),
                                                                        true)
                                                                .flatMap(
                                                                        context ->
                                                                                row(
                                                                                                project,
                                                                                                id,
                                                                                                true)
                                                                                        .flatMap(
                                                                                                source -> {
                                                                                                    if (request
                                                                                                                    == null
                                                                                                            || request
                                                                                                                            .input()
                                                                                                                    == null)
                                                                                                        return Mono
                                                                                                                .error(
                                                                                                                        error(
                                                                                                                                "split_preview_expired",
                                                                                                                                409));
                                                                                                    var
                                                                                                            prepared =
                                                                                                                    prepare(
                                                                                                                            source,
                                                                                                                            request
                                                                                                                                    .input(),
                                                                                                                            context,
                                                                                                                            today);
                                                                                                    if (!Objects
                                                                                                            .equals(
                                                                                                                    request
                                                                                                                            .token(),
                                                                                                                    prepared.preview()
                                                                                                                            .token()))
                                                                                                        return Mono
                                                                                                                .error(
                                                                                                                        error(
                                                                                                                                "split_preview_expired",
                                                                                                                                409));
                                                                                                    return publish(
                                                                                                            source,
                                                                                                            prepared,
                                                                                                            request
                                                                                                                    .input(),
                                                                                                            auth);
                                                                                                }))))
                .as(MonoTracer.create("/property/finance/receivable/split"));
    }

    private Prepared prepare(
            ReceivableEntity source,
            SplitInput input,
            PropertyHouseContext context,
            LocalDate today) {
        ReceivablePolicy.assertAllowed(source, "SPLIT");
        if (input == null || !Objects.equals(input.version(), source.getVersion()))
            throw error("version_conflict", 409);
        if (input.reason() == null || input.reason().isBlank() || input.reason().length() > 1000)
            throw error("split_invalid", 400);
        var children = rules.children(source, input);
        var relationships =
                List.of(
                        Objects.toString(input.firstRelationshipId(), ""),
                        Objects.toString(input.secondRelationshipId(), ""));
        for (int i = 0; i < children.size(); i++) {
            var child = children.get(i);
            var relationship = relationships.get(i);
            if (!relationship.equals(source.getCustomerRelationshipId())) {
                var customer =
                        context.customers().stream()
                                .filter(row -> row.relationshipId().equals(relationship))
                                .findFirst()
                                .orElseThrow(() -> error("split_customer_invalid", 400));
                child.setCustomerId(customer.customerId());
                child.setCustomerRelationshipId(customer.relationshipId());
                child.setCustomerName(customer.name());
                var snapshot = JSON.parseObject(child.getSnapshotJson());
                var customerSnapshot =
                        org.jetlinks.pro.property.finance.setting.SettingRules.customerSnapshot(
                                customer);
                snapshot.put("customer", customerSnapshot);
                snapshot.put("customers", List.of(customerSnapshot));
                child.setSnapshotJson(snapshot.toJSONString());
            }
        }
        var token =
                hash(
                        JSON.toJSONString(
                                List.of(
                                        source.getId(),
                                        source.getVersion(),
                                        input,
                                        today.toString(),
                                        context,
                                        children)));
        var items =
                children.stream()
                        .map(
                                child ->
                                        new Part(
                                                child.getCalcStartDate(),
                                                child.getCalcEndDate(),
                                                child.getCustomerRelationshipId(),
                                                child.getCustomerName(),
                                                child.getActualReceivableAmount().toPlainString()))
                        .toList();
        return new Prepared(
                children,
                new Preview(token, source.getActualReceivableAmount().toPlainString(), items));
    }

    private Mono<List<ReceivableView>> publish(
            ReceivableEntity source, Prepared prepared, SplitInput input, Authentication auth) {
        var before = ReceivableService.auditSnapshot(source);
        for (int i = 0; i < prepared.children().size(); i++) {
            var child = prepared.children().get(i);
            child.setId(UUID.randomUUID().toString());
            init(child, auth);
            child.setBillKey("SPLIT:" + source.getId() + ":" + i);
        }
        var log = new ReceivableLogEntity();
        log.setId(UUID.randomUUID().toString());
        log.setProjectId(source.getProjectId());
        log.setSpaceId(source.getSpaceId());
        log.setReceivableId(source.getId());
        log.setOperation("SPLIT");
        log.setReason(input.reason().trim());
        log.setCreateTime(System.currentTimeMillis());
        log.setOperatorId(auth.getUser().getId());
        log.setOperatorName(auth.getUser().getUsername());
        log.setBeforeVersion(source.getVersion());
        log.setBeforeJson(before);
        source.setSplit(true);
        source.setVersion(source.getVersion() + 1);
        log.setAfterVersion(source.getVersion());
        log.setAfterJson(ReceivableService.auditSnapshot(source));
        log.setDetailJson(
                JSON.toJSONString(
                        Map.of(
                                "input",
                                input,
                                "children",
                                prepared.children().stream()
                                        .map(
                                                child ->
                                                        Map.of(
                                                                "id",
                                                                child.getId(),
                                                                "start",
                                                                child.getCalcStartDate(),
                                                                "end",
                                                                child.getCalcEndDate(),
                                                                "customerName",
                                                                child.getCustomerName(),
                                                                "amount",
                                                                child.getActualReceivableAmount()
                                                                        .toPlainString()))
                                        .toList())));
        return audit(bills.createUpdate(), auth, input.version())
                .set("split", true)
                .where("id", source.getId())
                .and("projectId", source.getProjectId())
                .and("deleted", false)
                .and("version", input.version())
                .execute()
                .flatMap(SubjectService::changed)
                .thenMany(
                        Flux.fromIterable(prepared.children())
                                .concatMap(child -> bills.insert(child)))
                .then(logs.insert(log))
                .thenReturn(
                        prepared.children().stream()
                                .map(child -> ReceivableView.of(child, false))
                                .toList());
    }

    private Mono<ReceivableEntity> row(String project, String id, boolean lock) {
        var query = new QueryParamEntity();
        query.setForUpdate(lock);
        return bills.createQuery()
                .setParam(query)
                .where("id", id)
                .and("projectId", project)
                .and("deleted", false)
                .fetchOne()
                .switchIfEmpty(Mono.error(error("receivable_not_found", 404)))
                .flatMap(
                        row ->
                                AssetsHolder.assertPermission(
                                                AssetType.of("space"),
                                                row.getSpaceId(),
                                                CrudAssetPermission.save)
                                        .thenReturn(row));
    }

    private static String hash(String source) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private record Prepared(List<ReceivableEntity> children, Preview preview) {}

    public record CustomerOption(String relationshipId, String name) {}

    public record Part(
            String start, String end, String relationshipId, String customerName, String amount) {}

    public record Preview(String token, String total, List<Part> parts) {}

    public record Submit(SplitInput input, String token) {}
}
