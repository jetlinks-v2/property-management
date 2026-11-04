package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;

import com.alibaba.fastjson.JSON;

import lombok.RequiredArgsConstructor;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.jetlinks.pro.assets.AssetType;
import org.jetlinks.pro.assets.AssetsHolder;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.base.archive.PropertyHouseContext;
import org.jetlinks.pro.property.base.archive.PropertyHouseCustomer;
import org.jetlinks.pro.property.finance.entity.FundAccountEntity;
import org.jetlinks.pro.property.finance.entity.StandardEntity;
import org.jetlinks.pro.property.finance.payment.FundsInput;
import org.jetlinks.pro.property.finance.setting.SettingRules;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/** 通过基础 core 契约解析有效客户；历史余额退还不要求原客户仍在房屋中。 */
@Component
@RequiredArgsConstructor
public class FundSourceService {
    private final HouseArchiveSource archive;
    private final CollectionStandardSource collectionStandards;
    private final ReactiveRepository<FundAccountEntity, String> accounts;

    Mono<Prepared> prepare(String project, FundsInput input, boolean lock) {
        Mono<Prepared> source =
                "TOPUP".equals(input.getAction())
                        ? topup(project, input, lock)
                        : historical(project, input, lock);
        return source.flatMap(prepared -> target(project, input, prepared, lock));
    }

    private Mono<Prepared> topup(String project, FundsInput input, boolean lock) {
        return current(
                        project,
                        input.getType(),
                        input.getSpaceId(),
                        input.getRelationshipId(),
                        input.getStandardId(),
                        input.getCompanyId(),
                        lock)
                .flatMap(
                        account ->
                                standard(
                                                project,
                                                input.getStandardId(),
                                                input.getCompanyId(),
                                                false)
                                        .map(
                                                standard -> {
                                                    validateTopup(input, standard);
                                                    return new Prepared(
                                                            account,
                                                            null,
                                                            standard.getId()
                                                                    + ":"
                                                                    + standard.getVersion());
                                                }));
    }

    private void validateTopup(FundsInput input, StandardEntity standard) {
        if (input.getQuantity() != null) {
            var expected =
                    standard.getPrice()
                            .multiply(input.getQuantity())
                            .setScale(
                                    standard.getChargeRoundType(), SettingRules.rounding(standard));
            if (expected.compareTo(input.getAmount()) != 0)
                throw error("fund_quantity_mismatch", 400);
        }
        var date = input.getOccurredAt().substring(0, 10);
        if (standard.getExecuteDate() != null && standard.getExecuteDate().compareTo(date) > 0
                || standard.getCancelDate() != null && standard.getCancelDate().compareTo(date) < 0)
            throw error("setting_standard_unavailable", 409);
    }

    private Mono<Prepared> historical(String project, FundsInput input, boolean lock) {
        return account(project, input.getAccountId(), false)
                .flatMap(
                        account -> {
                            if (!Objects.equals(account.getType(), input.getType())
                                    || !Objects.equals(
                                            account.getCompanyId(), input.getCompanyId()))
                                return Mono.error(error("fund_source_invalid", 409));
                            if (!Objects.equals(account.getVersion(), input.getVersion()))
                                return Mono.error(error("version_conflict", 409));
                            if (!"OFFSET".equals(input.getAction()))
                                return Mono.just(new Prepared(account, null, ""));
                            return archive.context(project, account.getSpaceId(), lock)
                                    .doOnNext(
                                            context -> {
                                                if (context.customers().stream()
                                                        .noneMatch(
                                                                customer ->
                                                                        customer.customerId()
                                                                                .equals(
                                                                                        account
                                                                                                .getCustomerId())))
                                                    throw error("cashier_payer_invalid", 409);
                                                account.setSourceIdentity(context.toString());
                                            })
                                    .thenReturn(new Prepared(account, null, ""));
                        });
    }

    private Mono<Prepared> target(String project, FundsInput input, Prepared source, boolean lock) {
        if ("TRANSFER".equals(input.getAction()))
            return current(
                            project,
                            input.getTargetType(),
                            input.getTargetSpaceId(),
                            input.getTargetRelationshipId(),
                            input.getTargetStandardId(),
                            input.getCompanyId(),
                            lock)
                    .map(
                            target -> {
                                if (target.getId().equals(source.source().getId()))
                                    throw error("fund_same_account", 400);
                                return new Prepared(
                                        source.source(),
                                        target,
                                        source.referenceIdentity()
                                                + ":"
                                                + input.getTargetStandardId());
                            });
        if ("DEDUCT".equals(input.getAction()))
            return standard(project, input.getTargetStandardId(), input.getCompanyId(), false)
                    .thenReturn(source);
        return Mono.just(source);
    }

    private Mono<FundAccountEntity> current(
            String project,
            String type,
            String space,
            String relationship,
            String standardId,
            String company,
            boolean lock) {
        return AssetsHolder.assertPermission(AssetType.of("space"), space, CrudAssetPermission.save)
                .then(archive.context(project, space, lock))
                .flatMap(
                        context ->
                                fromContext(
                                        project,
                                        type,
                                        space,
                                        relationship,
                                        standardId,
                                        company,
                                        context));
    }

    private Mono<FundAccountEntity> fromContext(
            String project,
            String type,
            String space,
            String relationship,
            String standardId,
            String company,
            PropertyHouseContext context) {
        var customer =
                context.customers().stream()
                        .filter(value -> value.relationshipId().equals(relationship))
                        .findFirst()
                        .orElseThrow(() -> error("cashier_payer_invalid", 409));
        if (!project.equals(context.house().projectId())
                || !space.equals(context.house().spaceId()))
            return Mono.error(error("fund_source_invalid", 409));
        return standard(project, standardId, company, false)
                .flatMap(
                        standard -> {
                            var key =
                                    JSON.toJSONString(
                                            List.of(
                                                    project,
                                                    company,
                                                    type,
                                                    space,
                                                    customer.customerId(),
                                                    standard.getSubjectId()));
                            var id =
                                    UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8))
                                            .toString();
                            return accounts.findById(id)
                                    .filter(account -> !Boolean.TRUE.equals(account.getDeleted()))
                                    .switchIfEmpty(
                                            Mono.fromSupplier(
                                                    () ->
                                                            newAccount(
                                                                    id, project, company, type,
                                                                    space, customer, standard)))
                                    .map(
                                            account -> {
                                                if (account.getBalance().signum() > 0
                                                        && !standardId.equals(
                                                                account.getStandardId()))
                                                    throw error("fund_standard_conflict", 409);
                                                // 零余额可以使用新标准；旧资金流水保留各自的名称和标准快照。
                                                account.setStandardId(standardId);
                                                account.setStandardName(standard.getStandardName());
                                                account.setSubjectName(standard.getSubjectName());
                                                account.setFullName(context.house().fullName());
                                                account.setRelationshipId(relationship);
                                                account.setCustomerName(customer.name());
                                                account.setSourceIdentity(
                                                        context.house().version()
                                                                + ":"
                                                                + customer.relationshipVersion()
                                                                + ":"
                                                                + customer.customerVersion());
                                                return account;
                                            });
                        });
    }

    private FundAccountEntity newAccount(
            String id,
            String project,
            String company,
            String type,
            String space,
            PropertyHouseCustomer customer,
            StandardEntity standard) {
        var account = new FundAccountEntity();
        account.setId(id);
        account.setVersion(0L);
        account.setProjectId(project);
        account.setCompanyId(company);
        account.setType(type);
        account.setSpaceId(space);
        account.setCustomerId(customer.customerId());
        account.setSubjectId(standard.getSubjectId());
        account.setBalance(BigDecimal.ZERO.setScale(2));
        account.setInvoiceOccupiedAmount(BigDecimal.ZERO.setScale(2));
        return account;
    }

    Mono<StandardEntity> standard(String project, String id, String company, boolean unused) {
        return collectionStandards.current(project, id, company);
    }

    Mono<Void> lockReferences(String project, FundsInput input) {
        var ids = new TreeSet<String>();
        if ("TOPUP".equals(input.getAction())) ids.add(input.getStandardId());
        if (Set.of("TRANSFER", "DEDUCT").contains(input.getAction()))
            ids.add(input.getTargetStandardId());
        return collectionStandards.lock(project, input.getCompanyId(), ids);
    }

    Mono<FundAccountEntity> account(String project, String id, boolean lock) {
        var query = new QueryParamEntity();
        query.setForUpdate(lock);
        return accounts.createQuery()
                .setParam(query)
                .where("id", id)
                .and("projectId", project)
                .and("deleted", false)
                .fetchOne()
                .switchIfEmpty(Mono.error(error("fund_not_found", 404)))
                .flatMap(
                        account ->
                                AssetsHolder.assertPermission(
                                                AssetType.of("space"),
                                                account.getSpaceId(),
                                                CrudAssetPermission.save)
                                        .thenReturn(account));
    }

    /** 资金账户按 ID 排序加锁，之后不再申请上游锁；保留本次通过校验的当前资料。 */
    Mono<Prepared> lock(String project, Prepared source) {
        var values = new ArrayList<FundAccountEntity>();
        values.add(source.source());
        if (source.target() != null) values.add(source.target());
        return Flux.fromIterable(
                        values.stream()
                                .sorted(Comparator.comparing(FundAccountEntity::getId))
                                .toList())
                .concatMap(account -> lockedAccount(project, account))
                .collectMap(FundAccountEntity::getId)
                .map(
                        locked ->
                                new Prepared(
                                        locked.get(source.source().getId()),
                                        source.target() == null
                                                ? null
                                                : locked.get(source.target().getId()),
                                        source.referenceIdentity()));
    }

    private Mono<FundAccountEntity> lockedAccount(String project, FundAccountEntity candidate) {
        Mono<FundAccountEntity> locked =
                candidate.getCreateTime() == null
                        ? accounts.findById(candidate.getId())
                                .flatMap(found -> account(project, found.getId(), true))
                                .switchIfEmpty(Mono.just(candidate))
                        : account(project, candidate.getId(), true);
        return locked.map(
                account -> {
                    if (candidate.getSourceIdentity() != null) {
                        account.setSourceIdentity(candidate.getSourceIdentity());
                        account.setRelationshipId(candidate.getRelationshipId());
                        account.setFullName(candidate.getFullName());
                        account.setCustomerName(candidate.getCustomerName());
                        account.setStandardId(candidate.getStandardId());
                        account.setStandardName(candidate.getStandardName());
                        account.setSubjectName(candidate.getSubjectName());
                    }
                    return account;
                });
    }

    record Prepared(FundAccountEntity source, FundAccountEntity target, String referenceIdentity) {}
}
