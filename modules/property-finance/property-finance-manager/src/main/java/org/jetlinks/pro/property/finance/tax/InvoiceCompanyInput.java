package org.jetlinks.pro.property.finance.tax;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;

/** 仅允许维护业务字段，不接收主键、项目、逻辑删除或审计信息，更新时必须提交 version。 */
@Getter
@Setter
public class InvoiceCompanyInput {
    private Long version;
    private String companyName;
    private String taxNumber;
    private String address;
    private String phone;
    private String depositBank;
    private String bankAccount;
    private String drawer;
    private String reviewer;
    private String estateNo;
    private String shop;
    private String legalPersonId;
    private String ratepayingNature;
    private String invoiceManufacturerCode;
    private String invoiceManufacturerName;
    private String invoiceExtensionNumber;
    private String departmentId;
    private String thirdpartyVendorId;
    private BigDecimal quota;
    private List<BankInput> banks;

    public record BankInput(
            String id,
            String bankName,
            String bankAccount,
            Boolean defaultBank,
            String financingInstitution,
            String jointLineNumber,
            String accountName,
            String accountNumber,
            String accountBookId) {}
}
