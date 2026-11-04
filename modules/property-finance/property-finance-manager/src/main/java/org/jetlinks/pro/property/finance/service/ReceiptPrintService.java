package org.jetlinks.pro.property.finance.service;

import lombok.RequiredArgsConstructor;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.i18n.LocaleUtils;
import org.jetlinks.core.trace.MonoTracer;
import org.jetlinks.pro.property.finance.document.ReceiptPdfRenderer;
import org.jetlinks.pro.property.finance.entity.DocumentOperationEntity;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** 授权后使用不可变开具快照打印；重印不产生票据或资金事实。 */
@Service
@RequiredArgsConstructor
public class ReceiptPrintService {
    private final DocumentIssueService documents;
    private final ReceiptPdfRenderer renderer;
    private final ReactiveRepository<DocumentOperationEntity, String> operations;

    public Mono<byte[]> pdf(String project, String id) {
        return documents
                .detail(project, "RECEIPT", id)
                .flatMap(this::renderAuthorized)
                .as(MonoTracer.create("property.finance.receipt.print"));
    }

    private Mono<byte[]> renderAuthorized(DocumentStore.Detail detail) {
        var row = detail.document();
        // 作废会更新主单modifier，开具人只能取首次开具的不可变审计，不能打印成作废操作人。
        return operations
                .createQuery()
                .where("projectId", row.getProjectId())
                .and("tenantId", row.getTenantId())
                .and("documentId", row.getId())
                .and("documentType", "RECEIPT")
                .and("action", "ISSUE")
                .and("deleted", false)
                .fetchOne()
                .switchIfEmpty(Mono.error(FinanceScope.error("document_record_invalid", 409)))
                .zipWith(
                        LocaleUtils.currentReactive()
                                .defaultIfEmpty(java.util.Locale.SIMPLIFIED_CHINESE))
                // PDF布局、字体读取和压缩具有阻塞/CPU开销，不占用R2DBC及HTTP事件线程。
                .flatMap(
                        pair ->
                                Mono.fromCallable(
                                                () ->
                                                        renderer.render(
                                                                detail,
                                                                pair.getT2(),
                                                                pair.getT1().getModifierName()))
                                        .subscribeOn(Schedulers.boundedElastic()));
    }
}
