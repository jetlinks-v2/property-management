package org.jetlinks.pro.smartpark.handover.excel;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.core.trace.MonoTracer;
import org.jetlinks.pro.io.file.FileManager;
import org.jetlinks.pro.smartpark.handover.api.FileRef;
import org.jetlinks.pro.smartpark.handover.api.ProblemRequest;
import org.jetlinks.pro.smartpark.handover.entity.*;
import org.springframework.core.io.buffer.*;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** 解析→全表业务预验→暂存图片→单事务写入；对象存储失败另行补偿，不冒充数据库事务。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class HandoverExcelService {
    private final HandoverImportCatalog catalog;
    private final HandoverImportWriter writer;
    private final FileManager files;

    public Flux<DataBuffer> template(String kind, String projectId, String source, String stage) {
        HandoverExcelSchema.validateSelector(kind, source, stage);
        if ("ledger".equals(kind)) return HandoverExcelPermission.assertImport(kind)
                .then(catalog.ledger(null)).flatMapMany(options -> HandoverWorkbook.template(kind, stage, options.options()));
        return HandoverExcelPermission.assertImport(kind)
                .then(catalog.load(projectId, source, kind))
                .flatMapMany(
                        options -> HandoverWorkbook.template(kind, stage, options.options(kind)));
    }

    public Mono<Map<String, Long>> importFile(
            String kind, String projectId, String source, String stage, FilePart file) {
        HandoverExcelSchema.validateSelector(kind, source, stage);
        if (!file.filename().toLowerCase(java.util.Locale.ROOT).endsWith(".xlsx"))
            return Mono.error(HandoverWorkbook.invalid(1, "文件", "请上传.xlsx文件"));
        if ("ledger".equals(kind)) return HandoverExcelPermission.assertImport(kind).then(read(file))
                .flatMap(bytes -> Mono.fromCallable(() -> HandoverWorkbook.read(bytes, kind)).subscribeOn(Schedulers.boundedElastic()))
                .flatMap(rows -> catalog.ledger(rows).flatMap(options -> Mono.fromCallable(() -> prepareLedger(rows, options))
                        .subscribeOn(Schedulers.boundedElastic())))
                .flatMap(this::preflight).flatMap(this::stageAndWrite).as(MonoTracer.create("handover.excel.ledger.import"));
        return HandoverExcelPermission.assertImport(kind)
                .then(catalog.load(projectId, source, kind))
                .flatMap(
                        options ->
                                read(file)
                                        .flatMap(
                                                bytes ->
                                                        Mono.fromCallable(
                                                                        () ->
                                                                                prepare(
                                                                                        kind,
                                                                                        projectId,
                                                                                        source,
                                                                                        stage,
                                                                                        bytes,
                                                                                        options))
                                                                .subscribeOn(
                                                                        Schedulers
                                                                                .boundedElastic()))
                                        .flatMap(this::preflight)
                                        .flatMap(this::stageAndWrite))
                .as(MonoTracer.create("handover.excel.import"));
    }

    private List<HandoverImportMapper.Prepared> prepareLedger(List<HandoverWorkbook.ImportRow> rows, HandoverImportCatalog.LedgerCatalog options) {
        var prepared = new ArrayList<HandoverImportMapper.Prepared>();
        var errors = new ArrayList<HandoverImportException.RowError>();
        for (var row : rows) {
            try { prepared.add(HandoverImportMapper.ledger(row, options)); }
            catch (HandoverImportException error) { errors.addAll(error.getErrors()); }
        }
        if (!errors.isEmpty()) throw new HandoverImportException(errors);
        return prepared;
    }

    private Mono<byte[]> read(FilePart file) {
        return DataBufferUtils.join(file.content(), HandoverWorkbook.MAX_BYTES)
                .map(
                        buffer -> {
                            try {
                                byte[] bytes = new byte[buffer.readableByteCount()];
                                buffer.read(bytes);
                                return bytes;
                            } finally {
                                DataBufferUtils.release(buffer);
                            }
                        })
                .onErrorMap(
                        DataBufferLimitException.class,
                        ignored -> HandoverWorkbook.invalid(1, "文件", "Excel文件超过32MB，请拆分后重试"));
    }

    private List<HandoverImportMapper.Prepared> prepare(
            String kind,
            String projectId,
            String source,
            String stage,
            byte[] bytes,
            HandoverImportCatalog.Catalog options) {
        var parsed = HandoverWorkbook.read(bytes, kind);
        var prepared = new ArrayList<HandoverImportMapper.Prepared>();
        var errors = new ArrayList<HandoverImportException.RowError>();
        var duplicate = new HashSet<String>();
        for (var row : parsed) {
            try {
                var mapped = HandoverImportMapper.map(kind, projectId, source, stage, row, options);
                if (mapped.value() instanceof HandoverCategoryEntity category
                        && !duplicate.add(category.getParentId() + "/" + category.getName()))
                    throw HandoverWorkbook.invalid(row.row(), "分类名称", "文件内同级分类重复");
                if (mapped.value() instanceof HandoverEquipmentEntity equipment
                        && equipment.getCode() != null
                        && !equipment.getCode().isBlank()
                        && !duplicate.add(equipment.getCode()))
                    throw HandoverWorkbook.invalid(row.row(), "设备编号", "文件内设备编号重复");
                prepared.add(mapped);
            } catch (HandoverImportException error) {
                errors.addAll(error.getErrors());
            }
        }
        if (!errors.isEmpty()) throw new HandoverImportException(errors);
        return prepared;
    }

    private Mono<List<HandoverImportMapper.Prepared>> preflight(
            List<HandoverImportMapper.Prepared> rows) {
        var errors = new ArrayList<HandoverImportException.RowError>();
        return Flux.fromIterable(rows)
                .concatMap(
                        row ->
                                Mono.defer(() -> writer.validate(row.value()))
                                        .onErrorResume(
                                                BusinessException.class,
                                                error -> {
                                                    // 仅业务校验转行错误；认证、目录连接和系统故障继续失败，不伪装为用户输入错误。
                                                    errors.add(
                                                            new HandoverImportException.RowError(
                                                                    row.row(),
                                                                    "业务校验",
                                                                    error.getMessage()));
                                                    return Mono.empty();
                                                }))
                .then(
                        Mono.fromSupplier(
                                () -> {
                                    if (!errors.isEmpty())
                                        throw new HandoverImportException(errors);
                                    return rows;
                                }));
    }

    private Mono<Map<String, Long>> stageAndWrite(List<HandoverImportMapper.Prepared> rows) {
        var stagedIds = new ArrayList<String>();
        return Flux.fromIterable(rows)
                .concatMap(row -> uploadImages(row, stagedIds))
                .then(writer.write(rows))
                .map(imported -> Map.of("total", (long) rows.size(), "imported", imported))
                .onErrorResume(error -> cleanup(stagedIds).then(Mono.error(error)));
    }

    private Mono<Void> uploadImages(HandoverImportMapper.Prepared row, List<String> stagedIds) {
        return Flux.fromIterable(row.images())
                .concatMap(
                        image -> {
                            String name =
                                    "查验导入-"
                                            + row.row()
                                            + "-"
                                            + java.util.UUID.randomUUID()
                                            + "."
                                            + image.extension();
                            return files.saveFile(
                                            name,
                                            Flux.defer(
                                                    () ->
                                                            Flux.just(
                                                                    DefaultDataBufferFactory
                                                                            .sharedInstance
                                                                            .wrap(image.bytes()))))
                                    .map(
                                            file -> {
                                                stagedIds.add(file.getId());
                                                var ref = new FileRef();
                                                ref.setFileId(file.getId());
                                                ref.setName(name);
                                                ref.setSize(file.getLength());
                                                ref.setType("image/" + image.extension());
                                                ref.setPhotoType("problem");
                                                return ref;
                                            });
                        })
                .collectList()
                .doOnNext(
                        refs -> {
                            for (int i = 0; i < refs.size(); i++) refs.get(i).setSortIndex(i);
                            if (row.value() instanceof ProblemRequest problem)
                                problem.setPhotos(refs);
                            else if (row.value() instanceof HandoverLedgerEntity ledger)
                                ledger.setFiles(refs);
                            else if (row.value() instanceof HandoverRecordEntity record)
                                record.setFiles(refs);
                        })
                .then();
    }

    private Mono<Void> cleanup(List<String> stagedIds) {
        return Flux.fromIterable(stagedIds)
                .concatMap(
                        id ->
                                files.delete(id)
                                        .onErrorResume(
                                                error -> {
                                                    log.error(
                                                            "查验导入暂存文件清理失败，需运维按平台文件生命周期回收，fileId={}",
                                                            id,
                                                            error);
                                                    return Mono.empty();
                                                }))
                .then();
    }
}
