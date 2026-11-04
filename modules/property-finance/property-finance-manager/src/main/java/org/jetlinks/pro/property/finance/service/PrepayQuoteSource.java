package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;

import com.alibaba.fastjson.JSON;

import lombok.RequiredArgsConstructor;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.jetlinks.pro.assets.AssetType;
import org.jetlinks.pro.assets.AssetsHolder;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.base.archive.*;
import org.jetlinks.pro.property.finance.activity.*;
import org.jetlinks.pro.property.finance.entity.*;
import org.jetlinks.pro.property.finance.payment.PaymentRules;
import org.jetlinks.pro.property.finance.setting.SettingRules;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** 通过公开房屋命令验证当前付款人，从冻结设置与既有账期事实建立只读报价。 */
@Component
@RequiredArgsConstructor
public class PrepayQuoteSource {
    private final HouseArchiveSource archive;
    private final ActivitySource activitySource;
    private final PrepayPeriodPricing pricing;
    private final ReceivableCalculationService calculation;
    private final ReactiveRepository<HouseSettingEntity, String> settings;
    private final ReactiveRepository<SubjectEntity, String> subjects;
    private final ReactiveRepository<ProjectSubjectEntity, String> issued;
    private final ReactiveRepository<ActivityEntity, String> activities;
    private final ReactiveRepository<ReceivableEntity, String> bills;
    private final BillingJobProperties billingProperties;

    Mono<PrepayQuoteSnapshot> prepare(String project, PrepayQuoteInput input, LocalDate date) {
        return setting(project, input.settingId(), false)
                .flatMap(
                        initial ->
                                AssetsHolder.assertPermission(
                                                AssetType.of("space"),
                                                initial.getSpaceId(),
                                                CrudAssetPermission.save)
                                        .then(archive.context(project, initial.getSpaceId(), true))
                                        .flatMap(
                                                context ->
                                                        setting(project, input.settingId(), true)
                                                                .flatMap(
                                                                        setting ->
                                                                                resolve(
                                                                                        setting,
                                                                                        context,
                                                                                        input,
                                                                                        date))));
    }

    private Mono<PrepayQuoteSnapshot> resolve(
            HouseSettingEntity setting,
            PropertyHouseContext context,
            PrepayQuoteInput input,
            LocalDate date) {
        if (!setting.getProjectId().equals(context.house().projectId())
                || !setting.getSpaceId().equals(context.house().spaceId())
                || !"ACTIVE".equals(setting.getStatus()))
            return Mono.error(error("prepay_source_changed", 409));
        var payer = payer(input, setting, context);
        return references(setting)
                .then(activity(setting.getProjectId(), setting, input, date))
                .flatMap(
                        activity ->
                                rows(setting)
                                        .map(
                                                rows ->
                                                        snapshot(
                                                                setting,
                                                                payer,
                                                                context,
                                                                input,
                                                                date,
                                                                activity.orElse(null),
                                                                rows)));
    }

    private Mono<HouseSettingEntity> setting(String project, String id, boolean lock) {
        var query = new QueryParamEntity();
        query.setForUpdate(lock);
        return settings.createQuery()
                .setParam(query)
                .where("projectId", project)
                .and("id", id)
                .and("deleted", false)
                .fetchOne()
                .switchIfEmpty(Mono.error(error("setting_not_found", 404)));
    }

    private Mono<Void> references(HouseSettingEntity setting) {
        return subjects.createQuery()
                .where("id", setting.getSubjectId())
                .and("deleted", false)
                .and("enabled", true)
                .fetchOne()
                .switchIfEmpty(Mono.error(error("prepay_source_changed", 409)))
                .then(
                        issued.createQuery()
                                .where("id", setting.getProjectSubjectId())
                                .and("projectId", setting.getProjectId())
                                .and("subjectId", setting.getSubjectId())
                                .and("deleted", false)
                                .fetchOne()
                                .switchIfEmpty(Mono.error(error("prepay_source_changed", 409))))
                .then();
    }

    private static PropertyHouseCustomer payer(
            PrepayQuoteInput input, HouseSettingEntity setting, PropertyHouseContext context) {
        var selected = SettingRules.customers(setting.getSnapshotJson());
        return context.customers().stream()
                .filter(c -> input.payerRelationshipId().equals(c.relationshipId()))
                .filter(
                        c ->
                                selected.stream()
                                        .anyMatch(
                                                saved ->
                                                        c.relationshipId()
                                                                        .equals(
                                                                                saved.get(
                                                                                        "relationshipId"))
                                                                && c.customerId()
                                                                        .equals(
                                                                                saved.get(
                                                                                        "customerId"))))
                .findFirst()
                .orElseThrow(() -> error("prepay_payer_invalid", 409));
    }

    private Mono<Optional<ActivityEntity>> activity(
            String project, HouseSettingEntity setting, PrepayQuoteInput input, LocalDate date) {
        if (input.activityId() == null) return Mono.just(Optional.empty());
        var query = new QueryParamEntity();
        query.setForUpdate(true);
        return activities
                .createQuery()
                .setParam(query)
                .where("projectId", project)
                .and("id", input.activityId())
                .and("deleted", false)
                .fetchOne()
                .switchIfEmpty(Mono.error(error("prepay_activity_invalid", 409)))
                .flatMap(
                        row -> {
                            var config =
                                    ActivityCodec.decode(
                                                    row.getConfigJson(), ActivitySnapshot.class)
                                            .getInput();
                            if (!Boolean.TRUE.equals(row.getEnabled())
                                    || date.isBefore(ActivityRules.date(row.getStartDate()))
                                    || date.isAfter(ActivityRules.date(row.getEndDate()))
                                    || !setting.getSubjectId().equals(row.getSubjectId())
                                    || !setting.getStandardId().equals(row.getStandardId())
                                    || !config.getTiers()
                                            .contains(
                                                    new ActivityInput.Tier(
                                                            input.paidMonths(),
                                                            input.giftMonths())))
                                return Mono.error(error("prepay_activity_invalid", 409));
                            return activitySource
                                    .spaces(project)
                                    .map(
                                            spaces -> {
                                                var parents = new HashMap<String, String>();
                                                for (var space : spaces)
                                                    parents.put(space.id(), space.parentId());
                                                var seen = new HashSet<String>();
                                                String cursor = setting.getSpaceId();
                                                while (cursor != null && seen.add(cursor)) {
                                                    if (config.getTargetIds().contains(cursor))
                                                        return Optional.of(row);
                                                    cursor = parents.get(cursor);
                                                }
                                                throw error("prepay_activity_invalid", 409);
                                            });
                        });
    }

    private Mono<List<ReceivableEntity>> rows(HouseSettingEntity setting) {
        // 不过滤deleted和split：逻辑删除/拆分仍占自然账期，不能以预缴绕过原账身份。
        var query = new QueryParamEntity();
        query.toQuery().orderByAsc("periodStart").orderByAsc("id");
        query.setPaging(true);
        query.setPageSize(10001);
        return bills.createQuery()
                .setParam(query)
                .where("projectId", setting.getProjectId())
                .and("spaceId", setting.getSpaceId())
                .and("subjectId", setting.getSubjectId())
                .and("standardId", setting.getStandardId())
                .fetch()
                .collectList()
                .map(
                        rows -> {
                            if (rows.size() > 10000) throw error("prepay_range_too_large", 400);
                            return rows;
                        });
    }

    private PrepayQuoteSnapshot snapshot(
            HouseSettingEntity setting,
            PropertyHouseCustomer payer,
            PropertyHouseContext context,
            PrepayQuoteInput input,
            LocalDate date,
            ActivityEntity activity,
            List<ReceivableEntity> rows) {
        var standard = SettingRules.standard(setting.getSnapshotJson());
        var method = standard.getSettlementMethod();
        if (!Set.of("MONTHLY", "QUARTERLY").contains(Objects.toString(method, "")))
            throw error("prepay_invalid", 400);
        int step = "QUARTERLY".equals(method) ? 3 : 1;
        var currentStart = date.withDayOfMonth(1);
        if (step == 3)
            currentStart = currentStart.withMonth(((date.getMonthValue() - 1) / 3) * 3 + 1);
        var arrears = new ArrayList<PrepayQuoteRules.Candidate>();
        var future = new ArrayList<PrepayQuoteRules.Candidate>();
        var selectedRows = new HashMap<String, ReceivableEntity>();
        var billKeys = new HashMap<String, String>();
        ActivityInput config =
                activity == null
                        ? null
                        : ActivityCodec.decode(activity.getConfigJson(), ActivitySnapshot.class)
                                .getInput();
        if (config != null && Boolean.TRUE.equals(config.getOffsetArrears())) {
            for (var row : rows) {
                if (!setting.getId().equals(row.getSettingId())
                        || !eligible(row)
                        || !SettingRules.selectedCustomer(row, payer.customerId())
                        || row.getCalcEndDate().compareTo(currentStart.toString()) >= 0
                        || row.getCalcStartDate().compareTo(config.getOffsetStartDate()) < 0
                        || row.getCalcEndDate().compareTo(config.getOffsetEndDate()) > 0) continue;
                var copy = new ReceivableEntity();
                BeanUtils.copyProperties(row, copy);
                calculation.calculate(
                        copy,
                        SettingRules.standard(row.getSnapshotJson()),
                        date,
                        org.jetlinks.pro.property.finance.setting.BillingTaskKind.ALL);
                // 沿旧欠费充赠资格：仅完整未付本金；调整、优惠或滞纳金后的欠费走普通收款。
                if (copy.getArrears().signum() <= 0
                        || copy.getArrears().compareTo(copy.getContractReceivableAmount()) != 0)
                    continue;
                arrears.add(
                        new PrepayQuoteRules.Candidate(
                                "ARREARS",
                                row.getId(),
                                LocalDate.parse(row.getCalcStartDate()),
                                LocalDate.parse(row.getCalcEndDate()),
                                copy.getArrears()));
                selectedRows.put(row.getId(), row);
                billKeys.put(row.getCalcStartDate(), row.getBillKey());
            }
        }
        var windows =
                PrepayQuoteRules.plan(
                        method,
                        date,
                        LocalDate.parse(setting.getEffectiveStartDate()),
                        SettingRules.date(setting.getEffectiveEndDate(), false),
                        2400);
        int needed = (input.paidMonths() + input.giftMonths()) / step;
        for (var window : windows) {
            if (future.size() + arrears.size() >= needed) break;
            var overlaps =
                    rows.stream()
                            .filter(
                                    row ->
                                            SettingRules.overlaps(
                                                    row.getCalcStartDate(),
                                                    row.getCalcEndDate(),
                                                    window.start().toString(),
                                                    window.end().toString()))
                            .toList();
            var priced = pricing.price(setting, window);
            ReceivableEntity existing = null;
            if (!overlaps.isEmpty()) {
                if (overlaps.size() != 1) continue;
                existing = overlaps.get(0);
                if (!priced.draft().getBillKey().equals(existing.getBillKey())
                        || !eligible(existing)
                        || !SettingRules.selectedCustomer(existing, payer.customerId())) continue;
            }
            // 既有未来账单可有手工调整，按其冻结事实重算整期；绝不以原设置覆盖人工调整。
            var amount = priced.amount();
            if (existing != null) {
                var copy = new ReceivableEntity();
                BeanUtils.copyProperties(existing, copy);
                calculation.calculate(
                        copy,
                        SettingRules.standard(copy.getSnapshotJson()),
                        window.end(),
                        org.jetlinks.pro.property.finance.setting.BillingTaskKind.ALL);
                amount = copy.getArrears();
                selectedRows.put(existing.getId(), existing);
            }
            billKeys.put(window.start().toString(), priced.draft().getBillKey());
            future.add(
                    new PrepayQuoteRules.Candidate(
                            window.start().isAfter(date) ? "FUTURE" : "CURRENT",
                            existing == null ? null : existing.getId(),
                            window.start(),
                            window.end(),
                            amount));
        }
        var quote =
                PrepayQuoteRules.calculate(
                        method, input.paidMonths(), input.giftMonths(), arrears, future);
        var periods =
                quote.lines().stream()
                        .map(
                                line -> {
                                    var candidate = line.period();
                                    // 公开命令仅提供当前有效关系；还必须覆盖报价最早一期，不能给后来迁入者追收原住户期间。
                                    if (payer.startTime() == null
                                            || Instant.ofEpochMilli(payer.startTime())
                                                    .atZone(ZoneId.of(billingProperties.getZone()))
                                                    .toLocalDate()
                                                    .isAfter(candidate.start()))
                                        throw error("prepay_payer_invalid", 409);
                                    var existing = selectedRows.get(candidate.receivableId());
                                    return new PrepayQuoteSnapshot.Period(
                                            candidate.type(),
                                            candidate.receivableId(),
                                            existing == null ? null : existing.getVersion(),
                                            existing == null
                                                    ? null
                                                    : PaymentRules.digest(
                                                            JSON.toJSONString(existing)),
                                            billKeys.get(candidate.start().toString()),
                                            candidate.start().toString(),
                                            candidate.end().toString(),
                                            candidate.originalAmount().toPlainString(),
                                            line.paidAmount().toPlainString(),
                                            line.discountAmount().toPlainString());
                                })
                        .toList();
        var sourceFingerprint =
                PaymentRules.digest(
                        context.house().version()
                                + ":"
                                + JSON.toJSONString(SettingRules.customerSnapshot(payer))
                                + ":"
                                + JSON.toJSONString(setting)
                                + ":"
                                + (activity == null ? "" : JSON.toJSONString(activity)));
        return new PrepayQuoteSnapshot(
                input,
                date.toString(),
                setting.getFullName(),
                payer.name(),
                payer.customerId(),
                sourceFingerprint,
                JSON.toJSONString(setting),
                activity == null ? null : JSON.toJSONString(activity),
                quote.payAmount().toPlainString(),
                quote.originalAmount().toPlainString(),
                periods);
    }

    private static boolean eligible(ReceivableEntity row) {
        return !Boolean.TRUE.equals(row.getDeleted())
                && !Boolean.TRUE.equals(row.getSplit())
                && !Boolean.TRUE.equals(row.getFrozen())
                && !Boolean.TRUE.equals(row.getFinancialClosed())
                && !Boolean.TRUE.equals(row.getPaymentLocked())
                && !Boolean.TRUE.equals(row.getBankCollectionLocked())
                && !Boolean.TRUE.equals(row.getDiscountLocked())
                && !Boolean.TRUE.equals(row.getChargeLocked())
                && !positive(row.getPaidAmount())
                && !positive(row.getPaymentOccupiedAmount())
                && !positive(row.getInvoiceOccupiedAmount());
    }

    private static boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }
}
