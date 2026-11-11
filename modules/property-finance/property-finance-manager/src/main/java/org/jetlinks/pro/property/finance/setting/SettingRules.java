package org.jetlinks.pro.property.finance.setting;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;

import com.alibaba.fastjson.JSON;

import org.jetlinks.pro.property.base.archive.*;
import org.jetlinks.pro.property.finance.entity.*;
import org.jetlinks.pro.property.finance.standard.StandardSnapshots;

import java.math.*;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.*;

/** 可信来源、日期、可编辑变量和快照共享规则；预览与正式保存使用同一路径。 */
public final class SettingRules {
    private static final Set<String> TRUSTED =
            Set.of("STANDARD_PRICE", "BILLING_AREA", "PERIOD_MONTHS");

    private SettingRules() {}

    public static LocalDate date(String input, boolean required) {
        if (input == null || input.isBlank()) {
            if (required) throw error("setting_invalid_dates", 400);
            return null;
        }
        try {
            if (!input.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))
                throw error("setting_invalid_dates", 400);
            return LocalDate.parse(input);
        } catch (DateTimeParseException failure) {
            throw error("setting_invalid_dates", 400);
        }
    }

    public static void validate(SettingInput input) {
        if (input == null
                || !id(input.getSpaceId())
                || !id(input.getProjectSubjectId())
                || !id(input.getStandardId())
                || input.getCustomerRelationshipIds() == null
                || input.getCustomerRelationshipIds().isEmpty()
                || input.getCustomerRelationshipIds().size() > 100
                || input.getCustomerRelationshipIds().stream().anyMatch(value -> !id(value))
                || new HashSet<>(input.getCustomerRelationshipIds()).size()
                        != input.getCustomerRelationshipIds().size()
                || input.getRemark() != null && input.getRemark().length() > 300)
            throw error("setting_invalid", 400);
        var start = date(input.getEffectiveStartDate(), true);
        var end = date(input.getEffectiveEndDate(), false);
        if (end != null && start.isAfter(end)) throw error("setting_invalid_dates", 400);
    }

    private static boolean id(String value) {
        return value != null && !value.isBlank() && value.length() <= 64;
    }

    public static HouseSettingEntity snapshot(
            String project,
            SettingInput input,
            StandardEntity standard,
            PropertyHouseContext context) {
        validate(input);
        if (!project.equals(context.house().projectId())
                || !input.getSpaceId().equals(context.house().spaceId()))
            throw error("setting_house_unavailable", 409);
        var customers =
                input.getCustomerRelationshipIds().stream()
                        .map(
                                id ->
                                        context.customers().stream()
                                                .filter(c -> id.equals(c.relationshipId()))
                                                .findFirst()
                                                .orElseThrow(
                                                        () ->
                                                                error(
                                                                        "setting_customer_unavailable",
                                                                        409)))
                        .toList();
        // 一份设置只出一笔应收；首客户字段保留既有账单检索锚点，缴款资格以完整对象快照为准。
        var customer = customers.get(0);
        var start = date(input.getEffectiveStartDate(), true);
        var end = date(input.getEffectiveEndDate(), false);
        var from = date(standard.getExecuteDate(), false);
        var until = date(standard.getCancelDate(), false);
        if (from != null && start.isBefore(from)
                || until != null && (end == null || end.isAfter(until)))
            throw error("setting_standard_dates", 409);
        var values = variables(input.getVariableValues(), standard, context.house());
        var row = new HouseSettingEntity();
        row.setProjectId(project);
        row.setSpaceId(input.getSpaceId());
        row.setFullName(context.house().fullName());
        row.setProjectSubjectId(input.getProjectSubjectId());
        row.setSubjectId(standard.getSubjectId());
        row.setSubjectName(standard.getSubjectName());
        row.setStandardId(standard.getId());
        row.setStandardCode(standard.getStandardCode());
        row.setStandardName(standard.getStandardName());
        row.setCustomerId(customer.customerId());
        row.setCustomerRelationshipId(customer.relationshipId());
        row.setCustomerName(customer.name());
        row.setEffectiveStartDate(start.toString());
        row.setEffectiveEndDate(end == null ? null : end.toString());
        row.setStatus("ACTIVE");
        row.setRemark(input.getRemark());
        var frozen = new LinkedHashMap<String, Object>();
        frozen.put("standard", standard);
        frozen.put("house", houseSnapshot(context.house()));
        frozen.put("customer", customerSnapshot(customer));
        frozen.put("customers", customers.stream().map(SettingRules::customerSnapshot).toList());
        frozen.put("values", values);
        row.setSnapshotJson(JSON.toJSONString(frozen));
        return row;
    }

    // 当前JSON组件不序列化record访问器，明确字段确保房屋/客户快照实际落库。
    public static Map<String, Object> houseSnapshot(PropertyHouseInfo house) {
        var result = new LinkedHashMap<String, Object>();
        result.put("projectId", house.projectId());
        result.put("spaceId", house.spaceId());
        result.put("name", house.name());
        result.put("fullName", house.fullName());
        result.put("spaceTypeId", house.spaceTypeId());
        result.put("spaceTypeName", house.spaceTypeName());
        result.put("spaceLocation", house.spaceLocation());
        result.put("billingArea", house.billingArea());
        result.put("buildingArea", house.buildingArea());
        result.put("insideArea", house.insideArea());
        result.put("deliveryDate", house.deliveryDate());
        result.put("version", house.version());
        return result;
    }

    public static Map<String, Object> customerSnapshot(PropertyHouseCustomer customer) {
        var result = new LinkedHashMap<String, Object>();
        result.put("relationshipId", customer.relationshipId());
        result.put("customerId", customer.customerId());
        result.put("userId", customer.userId());
        result.put("customerType", customer.customerType());
        result.put("name", customer.name());
        result.put("telephone", customer.telephone());
        result.put("nature", customer.nature());
        result.put("startTime", customer.startTime());
        result.put("relationshipVersion", customer.relationshipVersion());
        result.put("customerVersion", customer.customerVersion());
        return result;
    }

    /** 新设置明确保存多对象；已经持久化的单客户账单按原客户解析，不推断家庭或同房屋全员。 */
    public static List<Map<String, Object>> customers(String snapshot) {
        if (snapshot == null || snapshot.isBlank()) return List.of();
        var json = JSON.parseObject(snapshot);
        var rows = json.getJSONArray("customers");
        if (rows != null)
            return rows.stream()
                    .map(value -> (Map<String, Object>) JSON.parseObject(JSON.toJSONString(value)))
                    .toList();
        var customer = json.getJSONObject("customer");
        return customer == null || customer.getString("customerId") == null
                ? List.of()
                : List.of(customer);
    }

    public static boolean selectedCustomer(ReceivableEntity bill, String customerId) {
        var customers = customers(bill.getSnapshotJson());
        return customers.isEmpty()
                ? Objects.equals(customerId, bill.getCustomerId())
                : customers.stream()
                        .anyMatch(
                                customer -> Objects.equals(customerId, customer.get("customerId")));
    }

    private static Map<String, BigDecimal> variables(
            Map<String, String> input, StandardEntity standard, PropertyHouseInfo house) {
        var provided = input == null ? Map.<String, String>of() : input;
        var definitions = StandardSnapshots.variables(standard.getVariablesSnapshotJson());
        var editable = new HashSet<String>();
        var result = new LinkedHashMap<String, BigDecimal>();
        for (var variable : definitions) {
            String code = variable.variableCode();
            switch (code) {
                case "STANDARD_PRICE" -> result.put(code, standard.getPrice());
                case "BILLING_AREA" -> {
                    if (house.billingArea() == null || house.billingArea().signum() <= 0)
                        throw error("setting_house_unavailable", 409);
                    result.put(code, house.billingArea());
                }
                case "PERIOD_MONTHS" -> {
                    /* 每个实际账期计算，不接受调用者预填。 */
                }
                default -> {
                    editable.add(code);
                    String value = provided.get(code);
                    if (value == null || !value.matches("[0-9]{1,18}(\\.[0-9]{1,6})?"))
                        throw error("setting_variable_invalid", 400);
                    result.put(code, new BigDecimal(value));
                }
            }
        }
        if (provided.keySet().stream()
                .anyMatch(key -> TRUSTED.contains(key) || !editable.contains(key)))
            throw error("setting_variable_readonly", 400);
        return result;
    }

    public static StandardEntity standard(String snapshot) {
        return JSON.parseObject(snapshot).getObject("standard", StandardEntity.class);
    }

    public static Map<String, BigDecimal> values(String snapshot) {
        var values = JSON.parseObject(snapshot).getJSONObject("values");
        var result = new LinkedHashMap<String, BigDecimal>();
        values.forEach((key, value) -> result.put(key, new BigDecimal(value.toString())));
        return result;
    }

    public static Map<String, String> names(StandardEntity standard) {
        var result = new LinkedHashMap<String, String>();
        StandardSnapshots.variables(standard.getVariablesSnapshotJson())
                .forEach(
                        definition ->
                                result.put(definition.variableCode(), definition.variableName()));
        return result;
    }

    public static boolean overlaps(String aStart, String aEnd, String bStart, String bEnd) {
        return (aEnd == null || aEnd.compareTo(bStart) >= 0)
                && (bEnd == null || bEnd.compareTo(aStart) >= 0);
    }

    public static RoundingMode rounding(StandardEntity standard) {
        return standard.getMantissa() == 0
                ? RoundingMode.HALF_UP
                : standard.getMantissa() == 1 ? RoundingMode.DOWN : RoundingMode.UP;
    }

    public static BigDecimal money(BigDecimal amount) {
        if (amount == null
                || amount.signum() < 0
                || amount.compareTo(new BigDecimal("1000000000000000000")) >= 0)
            throw error("setting_amount_invalid", 400);
        return amount;
    }
}
