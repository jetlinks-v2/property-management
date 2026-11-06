package org.jetlinks.pro.property.finance.service;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;
import static org.jetlinks.pro.property.finance.service.InvoiceCompanyService.init;

import lombok.RequiredArgsConstructor;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.finance.entity.*;
import org.jetlinks.pro.property.finance.tax.TaxItemInput;
import org.jetlinks.pro.property.finance.tax.TaxItemRules;
import org.jetlinks.pro.property.finance.tax.TaxWorkbook;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.*;

/** 导入先留批次再校验；所有税目和成功状态在同一事务，失败批次单独保留，不报告部分成功。 */
@Service
@RequiredArgsConstructor
public class TaxImportService {
    private final FinanceScope scope;
    private final TaxItemService taxes;
    private final ReactiveRepository<TaxItemEntity, String> items;
    private final ReactiveRepository<TaxImportEntity, String> records;
    private final org.springframework.transaction.ReactiveTransactionManager transactionManager;

    public Mono<Result> upload(String projectId, String companyId, FilePart file) {
        return scope.project(projectId, CrudAssetPermission.save)
                .then(taxes.related(projectId, companyId, false))
                .then(scope.authenticated())
                .flatMap(
                        auth ->
                                Mono.defer(
                                        () -> {
                                            if (file == null
                                                    || file.filename().length() > 255
                                                    || !file.filename()
                                                            .toLowerCase(Locale.ROOT)
                                                            .matches(".*\\.(xls|xlsx)$"))
                                                return Mono.error(error("tax_file_format", 400));
                                            var record = new TaxImportEntity();
                                            record.setId(UUID.randomUUID().toString());
                                            init(record, auth);
                                            record.setProjectId(projectId);
                                            record.setCompanyId(companyId);
                                            record.setFileName(file.filename());
                                            record.setStatus("PROCESSING");
                                            record.setSuccessCount(0);
                                            record.setErrorCount(0);
                                            return records.insert(record)
                                                    .then(read(file, companyId))
                                                    .flatMap(
                                                            rows ->
                                                                    apply(
                                                                            projectId, companyId,
                                                                            record, rows))
                                                    .onErrorResume(
                                                            failure -> {
                                                                var update =
                                                                        records.createUpdate()
                                                                                .set(
                                                                                        "status",
                                                                                        "FAILED")
                                                                                .set(
                                                                                        "successCount",
                                                                                        0)
                                                                                .set(
                                                                                        "errorMessage",
                                                                                        failureMessage(
                                                                                                failure))
                                                                                .set(
                                                                                        "finishTime",
                                                                                        System
                                                                                                .currentTimeMillis());
                                                                if (failure
                                                                                instanceof
                                                                                org.hswebframework
                                                                                                        .web
                                                                                                        .exception
                                                                                                        .BusinessException
                                                                                                business
                                                                        && business.getArgs()
                                                                                != null
                                                                        && business.getArgs().length
                                                                                >= 3
                                                                        && business.getArgs()[1]
                                                                                instanceof
                                                                                Integer total
                                                                        && business.getArgs()[2]
                                                                                instanceof
                                                                                Integer invalid)
                                                                    update.set("totalCount", total)
                                                                            .set(
                                                                                    "errorCount",
                                                                                    invalid);
                                                                return update.where(
                                                                                "id",
                                                                                record.getId())
                                                                        .execute()
                                                                        .then(Mono.error(failure));
                                                            });
                                        }));
    }

    /** 公司行锁串行化手工及批量写入；匹配旧业务键更新并重新启用，已逻辑删除的身份拒绝隐式恢复。 */
    Mono<Result> apply(
            String projectId, String companyId, TaxImportEntity record, List<TaxItemInput> rows) {
        return taxes.related(projectId, companyId, true)
                .thenMany(
                        Flux.fromIterable(rows)
                                .concatMap(
                                        input -> {
                                            var key = TaxItemRules.validate(input).getMatchKey();
                                            return items.createQuery()
                                                    .where("companyId", companyId)
                                                    .and("matchKey", key)
                                                    .fetchOne()
                                                    .flatMap(
                                                            old -> {
                                                                if (Boolean.TRUE.equals(
                                                                        old.getDeleted()))
                                                                    return Mono
                                                                            .<TaxItemEntity>error(
                                                                                    error(
                                                                                            "tax_deleted_match",
                                                                                            409));
                                                                input.setVersion(old.getVersion());
                                                                return taxes.update(
                                                                                projectId,
                                                                                old.getId(),
                                                                                input)
                                                                        .flatMap(
                                                                                saved ->
                                                                                        Boolean.TRUE
                                                                                                        .equals(
                                                                                                                saved
                                                                                                                        .getEnabled())
                                                                                                ? Mono
                                                                                                        .just(
                                                                                                                saved)
                                                                                                : taxes
                                                                                                        .state(
                                                                                                                projectId,
                                                                                                                saved
                                                                                                                        .getId(),
                                                                                                                saved
                                                                                                                        .getVersion(),
                                                                                                                true));
                                                            })
                                                    .switchIfEmpty(
                                                            Mono.defer(
                                                                    () ->
                                                                            taxes.create(
                                                                                    projectId,
                                                                                    input)));
                                        }))
                .then(
                        records.createUpdate()
                                .set("status", "SUCCESS")
                                .set("totalCount", rows.size())
                                .set("successCount", rows.size())
                                .set("errorCount", 0)
                                .set("finishTime", System.currentTimeMillis())
                                .where("id", record.getId())
                                .execute())
                .thenReturn(new Result(record.getId(), rows.size()))
                .as(TransactionalOperator.create(transactionManager)::transactional);
    }

    public Mono<byte[]> template(String projectId) {
        return scope.project(projectId, CrudAssetPermission.read)
                .then(
                        Mono.fromCallable(TaxWorkbook::template)
                                .subscribeOn(Schedulers.boundedElastic()));
    }

    public Mono<PagerResult<TaxImportEntity>> records(String projectId, QueryParamEntity input) {
        return scope.project(projectId, CrudAssetPermission.read)
                .then(
                        Mono.defer(
                                () -> {
                                    var query = TaxItemService.bounded(input);
                                    query.toNestQuery(nested -> nested.and("projectId", projectId));
                                    query.toQuery().orderByDesc("createTime");
                                    return org.hswebframework.web.crud.query.QueryHelper.queryPager(
                                            query, records::createQuery);
                                }));
    }

    private Mono<List<TaxItemInput>> read(FilePart file, String companyId) {
        return DataBufferUtils.join(file.content(), TaxWorkbook.MAX_BYTES)
                .switchIfEmpty(Mono.error(error("tax_file_empty", 400)))
                .map(
                        buffer -> {
                            try {
                                var bytes = new byte[buffer.readableByteCount()];
                                buffer.read(bytes);
                                return bytes;
                            } finally {
                                DataBufferUtils.release(buffer);
                            }
                        })
                .onErrorMap(DataBufferLimitException.class, failure -> error("tax_file_size", 400))
                .flatMap(
                        bytes ->
                                Mono.fromCallable(() -> TaxWorkbook.read(bytes, companyId))
                                        .subscribeOn(Schedulers.boundedElastic()));
    }

    private String failureMessage(Throwable failure) {
        var message =
                failure instanceof org.hswebframework.web.exception.BusinessException business
                        ? org.hswebframework.web.i18n.LocaleUtils.resolveMessage(
                                business.getMessage(),
                                business.getMessage() + " " + Arrays.toString(business.getArgs()),
                                business.getArgs())
                        : "error.property_finance_tax_file_format";
        if (message.length() <= 8000) return message;
        return message.substring(0, 7900)
                + org.hswebframework.web.i18n.LocaleUtils.resolveMessage(
                        "error.property_finance_tax_file_error_truncated", "（记录已截断，完整错误请查看本次导入响应）");
    }

    public record Result(String recordId, int count) {}
}
