package org.jetlinks.pro.property.finance.document;

import java.util.List;

/** 开具输入只含购方、所选来源和本次金额；销方、税率及商品信息由后端冻结来源解析。 */
public record DocumentIssueInput(
        String documentType,
        String companyId,
        Buyer buyer,
        String invoiceType,
        String remark,
        List<Item> items) {
    public record Buyer(
            String name,
            String taxNum,
            String phone,
            String email,
            String address,
            String depositBank,
            String bankAccount,
            String extensionNumber) {}

    public record Item(
            String sourceType,
            String sourceId,
            String amount,
            String collectionMethod,
            String differenceAmount,
            DifferenceVoucher voucher) {}

    public record DifferenceVoucher(
            String type,
            String number,
            String electronicInvoiceNumber,
            String invoiceCode,
            String invoiceNumber,
            String date,
            String amount,
            String remark,
            String attachmentFileId,
            String attachmentFileName) {}
}
