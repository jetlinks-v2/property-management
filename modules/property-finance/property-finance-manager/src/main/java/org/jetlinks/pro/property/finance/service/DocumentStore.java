package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;
import static org.jetlinks.pro.property.finance.service.InvoiceCompanyService.init;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.authorization.Authentication;
import org.jetlinks.pro.assets.*;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.finance.document.DocumentIssueInput;
import org.jetlinks.pro.property.finance.entity.*;
import org.jetlinks.pro.property.finance.payment.PaymentRules;
import org.springframework.stereotype.Component;

import reactor.core.publisher.*;

import java.math.BigDecimal;
import java.util.*;

/** 票据内部存储边界；金额和快照只由预览/原票生成，历史详情仍逐空间校验权限。 */
@Component
@RequiredArgsConstructor
public class DocumentStore {
    private final ReactiveRepository<DocumentEntity, String> documents;
    private final ReactiveRepository<DocumentLineEntity, String> lines;
    private final ReactiveRepository<DocumentOperationEntity, String> operations;
    private final ObjectMapper mapper;

    Mono<DocumentEntity> find(String project, String type, String id, String tenant, boolean lock) {
        if (id == null || id.isBlank() || id.length() > 64)
            return Mono.error(error("document_not_found", 404));
        var query = new QueryParamEntity();
        query.setPaging(false);
        query.setForUpdate(lock);
        return documents
                .createQuery()
                .setParam(query)
                .where("id", id)
                .and("projectId", project)
                .and("documentType", type)
                .and("tenantId", tenant)
                .and("deleted", false)
                .fetchOne();
    }

    Mono<DocumentEntity> require(
            String project, String type, String id, String tenant, boolean lock) {
        return find(project, type, id, tenant, lock)
                .switchIfEmpty(Mono.error(error("document_not_found", 404)));
    }

    Mono<List<DocumentLineEntity>> lines(DocumentEntity document, CrudAssetPermission permission) {
        var query = new QueryParamEntity();
        query.setPageSize(1001);
        query.orderBy("lineIndex");
        return lines.createQuery()
                .setParam(query)
                .where("projectId", document.getProjectId())
                .and("documentId", document.getId())
                .and("deleted", false)
                .fetch()
                .collectList()
                .flatMap(rows -> authorize(rows, permission));
    }

    private Mono<List<DocumentLineEntity>> authorize(
            List<DocumentLineEntity> rows, CrudAssetPermission permission) {
        if (rows.isEmpty() || rows.size() > 1000)
            return Mono.error(error("document_record_invalid", 409));
        var spaces = rows.stream().map(DocumentLineEntity::getSpaceId).distinct().toList();
        return AssetsHolder.currentReactive()
                .switchIfEmpty(Mono.error(error("permission_denied", 403)))
                .flatMap(
                        holder ->
                                holder.assertPermission(
                                        AssetType.of("space").getId(), spaces, permission))
                .thenReturn(rows);
    }

    Mono<Detail> detail(DocumentEntity document, CrudAssetPermission permission) {
        return lines(document, permission).map(rows -> view(document, rows));
    }

    Detail view(DocumentEntity document, List<DocumentLineEntity> rows) {
        return new Detail(
                document,
                decode(document.getBuyerJson(), DocumentIssueInput.Buyer.class),
                decode(document.getRuleJson(), DocumentRuleService.View.class),
                rows.stream()
                        .map(
                                line ->
                                        new Line(
                                                line,
                                                decode(
                                                        line.getLineSnapshotJson(),
                                                        DocumentPreparationService.Line.class)))
                        .toList());
    }

    Mono<Detail> replay(String project, String type, String id, String tenant, String fingerprint) {
        return find(project, type, id, tenant, false)
                .flatMap(
                        row -> {
                            if (!fingerprint.equals(row.getRequestFingerprint()))
                                return Mono.error(error("document_request_conflict", 409));
                            // 成功重放只验证当前资产权限，不再读取实时客户或已被自己占满的来源额度。
                            return detail(row, CrudAssetPermission.save);
                        });
    }

    Mono<Detail> operationReplay(
            String project,
            String type,
            String id,
            String original,
            String tenant,
            String fingerprint) {
        return operations
                .createQuery()
                .where("id", id)
                .and("projectId", project)
                .and("tenantId", tenant)
                .and("documentType", type)
                .and("action", "REVERSE")
                .fetchOne()
                .flatMap(
                        row -> {
                            if (!fingerprint.equals(row.getRequestFingerprint())
                                    || !original.equals(row.getDocumentId()))
                                return Mono.error(error("document_request_conflict", 409));
                            return require(project, type, row.getResultDocumentId(), tenant, false)
                                    .flatMap(doc -> detail(doc, CrudAssetPermission.save));
                        });
    }

    Mono<Detail> insertBlue(
            String id,
            String tenant,
            String project,
            String fingerprint,
            DocumentPreparationService.Prepared prepared,
            String now,
            Authentication auth) {
        var view = prepared.view();
        var row = new DocumentEntity();
        row.setId(id);
        init(row, auth);
        row.setTenantId(tenant);
        row.setProjectId(project);
        row.setCreatorId(auth.getUser().getId());
        row.setDocumentType(view.documentType());
        row.setChannel("INVOICE".equals(view.documentType()) ? "SIMULATED" : "INTERNAL");
        row.setStatus("INVOICE".equals(view.documentType()) ? "SIMULATED_ISSUED" : "ISSUED");
        // 模拟编号永不伪装为税务发票号码；正式收据编号池在独立编号功能中接入。
        row.setDocumentNumber(("INVOICE".equals(view.documentType()) ? "SIM-" : "SJ-") + id);
        row.setCompanyId(view.companyId());
        row.setCompanyName(view.companyName());
        row.setSellerTaxNum(view.sellerTaxNum());
        row.setCustomerId(view.customerId());
        row.setCustomerName(view.customerName());
        row.setInvoiceType(view.invoiceType());
        row.setIssueScene(view.issueScene());
        row.setIssuedAt(now);
        row.setRemark(prepared.input().remark());
        row.setRequestFingerprint(fingerprint);
        row.setBuyerJson(encode(view.buyer()));
        row.setRuleJson(encode(view.rule()));
        row.setAmount(new BigDecimal(view.amount()));
        row.setTaxAmount(new BigDecimal(view.taxAmount()));
        row.setNetAmount(new BigDecimal(view.netAmount()));
        var frozen = new ArrayList<DocumentLineEntity>();
        for (int index = 0; index < view.lines().size(); index++) {
            var source = prepared.sources().get(index);
            var input = prepared.input().items().get(index);
            var line = newLine(row, index, view.lines().get(index), auth);
            line.setOriginalSourceType(input.sourceType());
            line.setOriginalSourceId(input.sourceId());
            line.setSourceSnapshotJson(source.snapshotJson());
            frozen.add(line);
        }
        return documents
                .insert(row)
                .thenMany(Flux.fromIterable(frozen).concatMap(lines::insert))
                .then()
                .then(
                        operation(
                                id,
                                row,
                                row.getId(),
                                "ISSUE",
                                fingerprint,
                                null,
                                row.getStatus(),
                                row.getRemark(),
                                auth))
                .thenReturn(view(row, frozen));
    }

    Mono<DocumentEntity> insertRed(
            String id,
            String fingerprint,
            DocumentEntity blue,
            List<DocumentLineEntity> original,
            String now,
            String reason,
            Authentication auth) {
        var red = new DocumentEntity();
        org.springframework.beans.BeanUtils.copyProperties(blue, red);
        red.setId(id);
        init(red, auth);
        red.setCreatorId(auth.getUser().getId());
        red.setOriginalDocumentId(blue.getId());
        red.setReverseDocumentId(null);
        red.setStatus("SIMULATED_RED");
        red.setDocumentNumber("SIM-RED-" + id);
        red.setIssuedAt(now);
        red.setRemark(reason);
        red.setRequestFingerprint(fingerprint);
        red.setAmount(blue.getAmount().negate());
        red.setTaxAmount(blue.getTaxAmount().negate());
        red.setNetAmount(blue.getNetAmount().negate());
        var frozen = new ArrayList<DocumentLineEntity>();
        for (var old : original) {
            var line =
                    newLine(
                            red,
                            old.getLineIndex(),
                            negative(
                                    decode(
                                            old.getLineSnapshotJson(),
                                            DocumentPreparationService.Line.class)),
                            auth);
            line.setOriginalSourceType(old.getOriginalSourceType());
            line.setOriginalSourceId(old.getOriginalSourceId());
            line.setSourceSnapshotJson(old.getSourceSnapshotJson());
            frozen.add(line);
        }
        return documents
                .insert(red)
                .thenMany(Flux.fromIterable(frozen).concatMap(lines::insert))
                .then(Mono.just(red));
    }

    Mono<Void> operation(
            String id,
            DocumentEntity original,
            String result,
            String action,
            String fingerprint,
            String before,
            String after,
            String reason,
            Authentication auth) {
        var row = new DocumentOperationEntity();
        row.setId(id);
        init(row, auth);
        row.setTenantId(original.getTenantId());
        row.setProjectId(original.getProjectId());
        row.setDocumentId(original.getId());
        row.setResultDocumentId(result);
        row.setDocumentType(original.getDocumentType());
        row.setAction(action);
        row.setRequestFingerprint(fingerprint);
        row.setBeforeState(before);
        row.setAfterState(after);
        row.setReason(reason);
        return operations.insert(row).then();
    }

    static String requestKey(
            String tenant,
            String project,
            Authentication auth,
            String type,
            String entry,
            String request) {
        return PaymentRules.digest(
                String.join("\n", tenant, project, auth.getUser().getId(), type, entry, request));
    }

    private DocumentLineEntity newLine(
            DocumentEntity doc,
            int index,
            DocumentPreparationService.Line snapshot,
            Authentication auth) {
        var row = new DocumentLineEntity();
        row.setId(PaymentRules.digest(doc.getId() + ":" + index));
        init(row, auth);
        row.setProjectId(doc.getProjectId());
        row.setDocumentId(doc.getId());
        row.setLineIndex(index);
        row.setSpaceId(snapshot.spaceId());
        row.setSourceType(snapshot.sourceType());
        row.setSourceId(snapshot.sourceId());
        row.setLineSnapshotJson(encode(snapshot));
        row.setAmount(new BigDecimal(snapshot.amount()));
        row.setTaxAmount(new BigDecimal(snapshot.taxAmount()));
        row.setNetAmount(new BigDecimal(snapshot.netAmount()));
        return row;
    }

    private static DocumentPreparationService.Line negative(DocumentPreparationService.Line l) {
        return new DocumentPreparationService.Line(
                l.sourceType(),
                l.sourceId(),
                l.spaceId(),
                l.fullName(),
                l.customerId(),
                l.customerName(),
                l.subjectId(),
                l.subjectName(),
                l.standardId(),
                l.standardName(),
                l.startDate(),
                l.endDate(),
                l.allowedAmount(),
                l.paidAmount(),
                l.availableAmount(),
                negate(l.amount()),
                l.taxItemId(),
                l.goodsName(),
                l.goodsCode(),
                l.taxRate(),
                l.includingTaxFlag(),
                l.collectionMethod(),
                negate(l.differenceAmount()),
                negate(l.outputTax()),
                negate(l.taxAmount()),
                negate(l.netAmount()),
                l.voucher());
    }

    private static String negate(String value) {
        return new BigDecimal(value).negate().toPlainString();
    }

    String encode(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize document", e);
        }
    }

    private <T> T decode(String value, Class<T> type) {
        try {
            return mapper.readValue(value, type);
        } catch (JsonProcessingException e) {
            throw error("document_record_invalid", 409);
        }
    }

    static void validateRequest(String request) {
        if (request == null || !request.matches("[A-Za-z0-9_-]{8,100}"))
            throw error("document_input_invalid", 400);
    }

    static void validateType(String type) {
        if (!Set.of("INVOICE", "RECEIPT").contains(Objects.toString(type, "")))
            throw error("document_input_invalid", 400);
    }

    public record Line(DocumentLineEntity line, DocumentPreparationService.Line snapshot) {}

    public record Detail(
            DocumentEntity document,
            DocumentIssueInput.Buyer buyer,
            DocumentRuleService.View rule,
            List<Line> lines) {}
}
