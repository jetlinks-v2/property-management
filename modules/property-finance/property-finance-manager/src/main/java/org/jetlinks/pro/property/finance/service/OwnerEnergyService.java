package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.pro.command.CommandSupportManagerProviders;
import org.jetlinks.pro.property.facility.command.QueryOwnerFacilityEnergyCommand;
import org.jetlinks.pro.property.facility.model.OwnerEnergyMeter;
import org.jetlinks.pro.property.finance.entity.HouseSettingEntity;
import org.jetlinks.pro.property.finance.setting.SettingRules;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/** 业主能源与金额预存。只读或核验，不写余额/实收/应收；支付到账只能由独立支付流程确认。 */
@Service @RequiredArgsConstructor
public class OwnerEnergyService {
    private final OwnerHouseSource houses;
    private final PrepayOptionsService options;
    private final OwnerFinanceService finance;

    public Mono<Overview> overview(String project, String space, String month) {
        return Mono.defer(() -> {
            validateMonth(month);
            return houses.require(project, space).then(Mono.zip(
                settings(project, space), finance.accounts(project, space), usage(project, space, month)))
                .map(data -> new Overview(month, data.getT3().meters(), data.getT1(), data.getT2(), data.getT3().error()));
        });
    }

    /** 预存科目与客户从审批生效的房屋设置派生，不接收客户端科目、标准或客户ID。 */
    public Mono<DepositQuote> quote(String project, String space, DepositInput input) {
        return Mono.defer(() -> {
            String amount = validateAmount(input);
            return houses.require(project, space).flatMap(owner -> settings(project, space)
                .map(rows -> {
                    var selected = rows.stream().filter(row -> row.id().equals(input.settingId())).findFirst()
                        .orElseThrow(() -> error("owner_deposit_setting_unavailable",409));
                    return new DepositQuote(selected.id(), selected.subjectName(), selected.standardName(), amount,
                        owner.context().house().fullName(), owner.context().customers().get(0).name(), false);
                }));
        });
    }

    Mono<List<Setting>> settings(String project, String space) {
        return houses.require(project, space).flatMap(owner -> options.availableSettings(project, space)
            .map(rows -> rows.stream().filter(row -> eligible(row, owner.context().customers().get(0).customerId(), owner.context().customers().get(0).relationshipId(), LocalDate.now(ZoneId.of("Asia/Shanghai"))))
                .map(row -> {
                    var standard = SettingRules.standard(row.getSnapshotJson());
                    return new Setting(row.getId(), row.getSubjectId(), row.getSubjectName(), row.getStandardId(),
                        row.getStandardName(), SettingRules.energyType(standard), OwnerFinanceService.money(standard.getPrice()));
                }).toList()));
    }

    static boolean eligible(HouseSettingEntity row, String customer, String relationship, LocalDate date) {
        var standard = SettingRules.standard(row.getSnapshotJson());
        return SettingRules.energyType(standard) != null && !date.isBefore(LocalDate.parse(row.getEffectiveStartDate())) &&
            (row.getEffectiveEndDate() == null || !date.isAfter(LocalDate.parse(row.getEffectiveEndDate()))) &&
            SettingRules.customers(row.getSnapshotJson()).stream().anyMatch(c -> customer.equals(c.get("customerId")) && relationship.equals(c.get("relationshipId")));
    }

    private Mono<Usage> usage(String project, String space, String month) {
        var command = new QueryOwnerFacilityEnergyCommand();
        command.setProjectId(project); command.setSpaceId(space); command.setMonth(month);
        return CommandSupportManagerProviders.getCommandSupport(QueryOwnerFacilityEnergyCommand.SERVICE_ID)
            .switchIfEmpty(Mono.error(error("owner_energy_unavailable",503)))
            .flatMap(s -> s.execute(command)).map(rows -> new Usage(rows, null))
            // 采集服务不可用不阻断余额和预存设置；权限失败仍完整返回403，不能变成空数据。
            .onErrorResume(BusinessException.class, e -> e.getStatus() == 503 || e.getStatus() == 504
                ? Mono.just(new Usage(List.of(), org.hswebframework.web.i18n.LocaleUtils.resolveMessage("error.property_finance_owner_energy_unavailable"))) : Mono.error(e));
    }

    static String validateAmount(DepositInput input) {
        if (input == null || input.settingId() == null || input.settingId().isBlank() || input.settingId().length() > 64 ||
            input.amount() == null || !input.amount().matches("(?:0|[1-9][0-9]{0,5})(?:\\.[0-9]{1,2})?"))
            throw error("owner_deposit_amount_invalid",400);
        var amount = new BigDecimal(input.amount());
        if (amount.compareTo(BigDecimal.ONE) < 0 || amount.compareTo(new BigDecimal("999999")) > 0)
            throw error("owner_deposit_amount_invalid",400);
        return amount.setScale(2).toPlainString();
    }

    static void validateMonth(String value) {
        if (value == null || !value.matches("[0-9]{4}-(0[1-9]|1[0-2])")) throw error("owner_query_invalid",400);
        var month = YearMonth.parse(value); var current = YearMonth.now(ZoneId.of("Asia/Shanghai"));
        if (month.isAfter(current) || month.isBefore(current.minusMonths(11))) throw error("owner_query_invalid",400);
    }

    private record Usage(List<OwnerEnergyMeter> meters, String error) {}
    public record Overview(String month, List<OwnerEnergyMeter> meters, List<Setting> settings,
        List<OwnerFinanceService.Account> accounts, String usageError) {}
    public record Setting(String id, String subjectId, String subjectName, String standardId, String standardName, String energyType, String price) {}
    public record DepositInput(String settingId, String amount) {}
    public record DepositQuote(String settingId, String subjectName, String standardName, String amount, String houseName, String payerName, boolean paymentAvailable) {}
}
