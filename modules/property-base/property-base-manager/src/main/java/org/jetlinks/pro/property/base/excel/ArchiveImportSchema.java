package org.jetlinks.pro.property.base.excel;

import java.util.List;

/** 三种填写表与新增表单一致；内部ID仅在后端完成匹配后进入领域请求。 */
public final class ArchiveImportSchema {
    private ArchiveImportSchema() {}

    public static String customerType(String type) {
        if (!"person".equals(type) && !"company".equals(type))
            throw ArchiveExcel.failure("customer_type");
        return type;
    }

    public static List<String> columns(String type) {
        return switch (type) {
            case "house" ->
                    List.of(
                            "spacePath",
                            "houseType",
                            "houseState",
                            "billingArea",
                            "buildingArea",
                            "insideArea",
                            "deliveryDate",
                            "remark");
            case "person" ->
                    List.of(
                            "spacePath",
                            "personName",
                            "telephone",
                            "nature",
                            "address",
                            "email",
                            "certificateType",
                            "certificateNumber");
            case "company" ->
                    List.of(
                            "spacePath",
                            "companyName",
                            "telephone",
                            "email",
                            "address",
                            "creditCode",
                            "legalRepresentative",
                            "registeredAddress");
            default -> throw ArchiveExcel.failure("customer_type");
        };
    }
}
