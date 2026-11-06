package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

import org.jetlinks.core.trace.MonoTracer;
import org.jetlinks.pro.property.finance.document.*;
import org.jetlinks.pro.property.finance.payment.PaymentRules;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

/** 预览只读取冻结来源，不占额度；正式开具复用prepare并在公司锁之后持有来源锁。 */
@Service
@RequiredArgsConstructor
public class DocumentPreparationService {
    private final DocumentInputRules inputRules;
    private final DocumentRules rules;
    private final DocumentRuleService settings;
    private final DocumentSourceService sources;
    private final ObjectMapper mapper;

    public Mono<Preview> preview(String project, DocumentIssueInput input, LocalDateTime now) {
        return prepare(project, input, now, false)
                .map(Prepared::view)
                .as(MonoTracer.create("property.finance.document.preview"));
    }

    Mono<Prepared> prepare(
            String project, DocumentIssueInput input, LocalDateTime now, boolean lock) {
        return Mono.defer(
                () -> {
                    if (now == null) return Mono.error(error("document_input_invalid", 400));
                    var normalized = inputRules.normalize(input);
                    var refs =
                            normalized.items().stream()
                                    .map(
                                            item ->
                                                    new DocumentSourceService.Reference(
                                                            item.sourceType(), item.sourceId()))
                                    .toList();
                    return sources.load(project, refs, lock)
                            .flatMap(
                                    rows ->
                                            settings.effective(project, lock)
                                                    .map(
                                                            rule ->
                                                                    build(
                                                                            normalized,
                                                                            rows,
                                                                            rule,
                                                                            now)));
                });
    }

    private Prepared build(
            DocumentIssueInput input,
            List<DocumentSourceService.Source> rows,
            DocumentRuleService.View rule,
            LocalDateTime now) {
        return build(input, rows, rule, now, Map.of(), false);
    }

    /** 重开只将原票真实journal可释放的额度加回；预览不实际释放，不放宽普通开票时效。 */
    Mono<Prepared> prepareReopen(
            String project,
            DocumentIssueInput input,
            LocalDateTime now,
            boolean lock,
            Map<DocumentRules.CanonicalSource, BigDecimal> credits) {
        return Mono.defer(
                () -> {
                    var normalized = inputRules.normalize(input);
                    var refs =
                            normalized.items().stream()
                                    .map(
                                            item ->
                                                    new DocumentSourceService.Reference(
                                                            item.sourceType(), item.sourceId()))
                                    .toList();
                    return sources.load(project, refs, lock)
                            .flatMap(
                                    rows ->
                                            settings.effective(project, lock)
                                                    .map(
                                                            rule ->
                                                                    build(
                                                                            normalized,
                                                                            rows,
                                                                            rule,
                                                                            now,
                                                                            credits,
                                                                            true)));
                });
    }

    private Prepared build(
            DocumentIssueInput input,
            List<DocumentSourceService.Source> rows,
            DocumentRuleService.View rule,
            LocalDateTime now,
            Map<DocumentRules.CanonicalSource, BigDecimal> credits,
            boolean reopen) {
        var first = rows.get(0);
        var lines = new ArrayList<Line>();
        var markers = new ArrayList<Object>();
        var amount = BigDecimal.ZERO;
        var tax = BigDecimal.ZERO;
        var net = BigDecimal.ZERO;
        var scenes = new HashSet<String>();
        for (int index = 0; index < rows.size(); index++) {
            var source = rows.get(index);
            // 押金不参与新开票或新开收据；历史票据的读取和释放仍保留原快照。
            if (source.fund() != null && !"ADVANCE".equals(source.fund().account().getType()))
                throw error("document_deposit_not_allowed", 400);
            var item = input.items().get(index);
            assertCompatible(input, first, source);
            var gross = rules.amount(item.amount(), false);
            var credit = credits.getOrDefault(source.canonical(), BigDecimal.ZERO);
            if (credit.signum() < 0 || credit.compareTo(source.occupiedAmount()) > 0)
                throw error("document_occupation_conflict", 409);
            var available = source.availableAmount().add(credit);
            if (gross.compareTo(available) > 0) throw error("document_quota_insufficient", 409);
            if (!reopen
                    && "INVOICE".equals(input.documentType())
                    && !rules.withinTime(
                            source.paidAt(), now, rule.timeLimitType(), rule.limitDays()))
                throw error("document_expired", 409);
            var calculated =
                    rules.tax(
                            item.amount(),
                            source.tax().taxRate(),
                            item.collectionMethod(),
                            item.differenceAmount());
            lines.add(line(source, item, calculated, available));
            markers.add(marker(source));
            amount = amount.add(gross);
            tax = tax.add(calculated.taxAmount());
            net = net.add(calculated.netAmount());
            scenes.add(
                    source.paidAmount().signum() == 0
                            ? "BEFORE_PAYMENT"
                            : source.paidAmount().compareTo(source.allowedAmount()) >= 0
                                    ? "AFTER_PAYMENT"
                                    : "MIXED");
        }
        rules.amount(amount.toPlainString(), false);
        String scene = scenes.size() == 1 ? scenes.iterator().next() : "MIXED";
        // 来源版本、可用额度、冻结快照、规则及业务日全部进入指纹；不信任客户端传回的税额。
        var token =
                PaymentRules.digest(
                        encode(
                                List.of(
                                        input,
                                        rule,
                                        now.toLocalDate().toString(),
                                        markers,
                                        reopen)));
        var view =
                new Preview(
                        token,
                        input.documentType(),
                        input.companyId(),
                        first.tax().companyName(),
                        first.tax().sellerTaxNum(),
                        first.customerId(),
                        first.customerName(),
                        input.buyer(),
                        input.invoiceType(),
                        scene,
                        now.toLocalDate().toString(),
                        amount.toPlainString(),
                        tax.toPlainString(),
                        net.toPlainString(),
                        rule,
                        List.copyOf(lines));
        return new Prepared(input, rows, rule, view);
    }

    private static void assertCompatible(
            DocumentIssueInput input,
            DocumentSourceService.Source first,
            DocumentSourceService.Source row) {
        if (!input.companyId().equals(row.tax().companyId())
                || !first.projectId().equals(row.projectId())
                || !Objects.equals(first.customerId(), row.customerId())
                || !first.tax().companyName().equals(row.tax().companyName())
                || !first.tax().sellerTaxNum().equals(row.tax().sellerTaxNum()))
            throw error("document_sources_incompatible", 409);
    }

    private static Line line(
            DocumentSourceService.Source source,
            DocumentIssueInput.Item item,
            DocumentRules.TaxAmounts tax,
            BigDecimal available) {
        return new Line(
                source.canonical().type(),
                source.canonical().id(),
                source.spaceId(),
                source.fullName(),
                source.customerId(),
                source.customerName(),
                source.subjectId(),
                source.subjectName(),
                source.standardId(),
                source.standardName(),
                source.startDate(),
                source.endDate(),
                source.allowedAmount().toPlainString(),
                source.paidAmount().toPlainString(),
                available.toPlainString(),
                item.amount(),
                source.tax().taxItemId(),
                source.tax().goodsName(),
                source.tax().goodsCode(),
                source.tax().taxRate(),
                source.tax().includingTaxFlag(),
                item.collectionMethod(),
                item.differenceAmount(),
                tax.outputTax().toPlainString(),
                tax.taxAmount().toPlainString(),
                tax.netAmount().toPlainString(),
                item.voucher());
    }

    private static Map<String, Object> marker(DocumentSourceService.Source source) {
        var marker = new LinkedHashMap<String, Object>();
        marker.put("key", source.canonical());
        marker.put("snapshot", source.snapshotJson());
        marker.put("paidAt", source.paidAt() == null ? null : source.paidAt().toString());
        marker.put("allowed", source.allowedAmount().toPlainString());
        marker.put("paid", source.paidAmount().toPlainString());
        marker.put("occupied", source.occupiedAmount().toPlainString());
        marker.put("version", source.bill() == null ? null : source.bill().getVersion());
        if (source.fund() != null) {
            marker.put("creditVersion", source.fund().credit().getVersion());
            marker.put("remaining", source.fund().credit().getRemainingAmount().toPlainString());
        }
        marker.put(
                "lines",
                source.paymentLines().stream()
                        .map(
                                row ->
                                        List.of(
                                                row.getId(),
                                                row.getVersion(),
                                                row.getAmount().toPlainString(),
                                                row.getRefundedAmount().toPlainString(),
                                                row.getInvoiceOccupiedAmount().toPlainString()))
                        .toList());
        return marker;
    }

    private String encode(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("Cannot serialize document preview", invalid);
        }
    }

    record Prepared(
            DocumentIssueInput input,
            List<DocumentSourceService.Source> sources,
            DocumentRuleService.View rule,
            Preview view) {}

    public record Preview(
            String token,
            String documentType,
            String companyId,
            String companyName,
            String sellerTaxNum,
            String customerId,
            String customerName,
            DocumentIssueInput.Buyer buyer,
            String invoiceType,
            String issueScene,
            String businessDate,
            String amount,
            String taxAmount,
            String netAmount,
            DocumentRuleService.View rule,
            List<Line> lines) {}

    public record Line(
            String sourceType,
            String sourceId,
            String spaceId,
            String fullName,
            String customerId,
            String customerName,
            String subjectId,
            String subjectName,
            String standardId,
            String standardName,
            String startDate,
            String endDate,
            String allowedAmount,
            String paidAmount,
            String availableAmount,
            String amount,
            String taxItemId,
            String goodsName,
            String goodsCode,
            String taxRate,
            Integer includingTaxFlag,
            String collectionMethod,
            String differenceAmount,
            String outputTax,
            String taxAmount,
            String netAmount,
            DocumentIssueInput.DifferenceVoucher voucher) {}
}
