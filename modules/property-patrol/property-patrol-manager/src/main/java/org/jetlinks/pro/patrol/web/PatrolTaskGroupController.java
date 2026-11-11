package org.jetlinks.pro.patrol.web;

import jakarta.annotation.Nonnull;

import lombok.AllArgsConstructor;

import org.hswebframework.web.authorization.annotation.Resource;
import org.hswebframework.web.authorization.annotation.QueryAction;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.exception.BusinessException;
import org.hswebframework.web.crud.service.ReactiveCrudService;
import org.jetlinks.pro.assets.AssetType;
import org.jetlinks.pro.assets.crud.CorrelatesAssetsHolderQueryController;
import org.jetlinks.pro.patrol.asset.PatrolAssetType;
import org.jetlinks.pro.patrol.entity.PatrolGenerationEntity;
import org.jetlinks.pro.patrol.service.PatrolTaskGroupService;
import org.jetlinks.pro.patrol.service.PatrolRecordExportService;
import org.jetlinks.pro.patrol.service.PatrolExportImages;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.core.io.FileSystemResource;
import reactor.core.publisher.Flux;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.http.HttpHeaders;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;


import java.util.function.Function;
import java.util.Set;

/** PC 日任务组只读查询；沿计划资产校验，知道任务组 ID 不代表拥有访问权限。 */
@RestController
@AllArgsConstructor
@Validated
@RequestMapping("/park/patrol/task-groups")
@Resource(id = "patrol-execution", name = "巡更执行记录")
public class PatrolTaskGroupController
        implements CorrelatesAssetsHolderQueryController<PatrolGenerationEntity, String> {
    private final PatrolTaskGroupService service;
    private final PatrolRecordExportService recordExport;
    private final PatrolExportImages images;
    private final PatrolRecordExportJobs jobs;
    static final int EXPORT_LIMIT = 10000;

    /** 全量导出沿用列表的筛选与计划资产权限；超限明确失败，不静默丢失尾部数据。 */
    @PostMapping("/export")
    @QueryAction
    public Mono<ResponseEntity<byte[]>> export(@RequestBody QueryParamEntity query) {
        return generate(query, null).map(bytes -> ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=patrol-records.xlsx").body(bytes));
    }

    /** 新页面通过短请求查询生成状态，避免把网关超时误当作导出失败。 */
    @PostMapping("/export-jobs")
    @QueryAction
    public Mono<PatrolRecordExportJobs.Status> startExport(@RequestBody QueryParamEntity query) {
        return jobs.start(job -> generate(query, job));
    }

    @GetMapping("/export-jobs/{id}")
    @QueryAction
    public Mono<PatrolRecordExportJobs.Status> exportStatus(@PathVariable String id) { return jobs.status(id); }

    @GetMapping("/export-jobs/{id}/file")
    @QueryAction
    public Mono<ResponseEntity<FileSystemResource>> downloadExport(@PathVariable String id) {
        return jobs.download(id).map(file -> ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=patrol-records.xlsx").body(file));
    }

    private Mono<byte[]> generate(QueryParamEntity query, PatrolRecordExportJobs.Job job) {
        query.setPageIndex(0);
        query.setPageSize(EXPORT_LIMIT + 1);
        query.setPaging(true);
        // 导出列固定，列表的字段投影不能裁掉任务组ID或历史快照所需字段。
        query.setIncludes(Set.of());
        query.setExcludes(Set.of());
        return injectQueryParam(query)
                .flatMap(service::queryPager)
                .flatMap(page -> {
                    if (page.getTotal() > EXPORT_LIMIT) {
                        return Mono.error(new BusinessException("error.patrol_record_export_limit"));
                    }
                    if (job != null) {
                        job.total = page.getData().size();
                        job.plans = page.getData().stream().map(PatrolGenerationEntity::getPlanId).distinct().toList();
                    }
                    // POI 的压缩/临时文件 I/O 全部离开响应式线程；异常和取消同样释放工作簿。
                    return Mono.usingWhen(
                            Mono.fromCallable(PatrolTaskGroupExport::new).subscribeOn(Schedulers.boundedElastic()),
                            writer -> recordExport.batches(page.getData())
                                    .concatMap(batch -> Mono.fromCallable(() -> writer.missingImages(batch))
                                            .flatMapMany(ids -> Flux.fromIterable(ids).flatMap(id -> images.read(id)
                                                    .map(image -> java.util.Map.entry(id, image)), 4))
                                            .concatMap(entry -> Mono.fromRunnable(() -> {
                                                writer.addImage(entry.getKey(), entry.getValue());
                                                if (job != null && entry.getValue().bytes().length > 0) job.images.add(entry.getKey());
                                            }).subscribeOn(Schedulers.boundedElastic()), 1)
                                            .then(Mono.fromRunnable(() -> {
                                                writer.append(batch);
                                                if (job != null) job.processed.addAndGet(batch.groups().size());
                                            }).subscribeOn(Schedulers.boundedElastic())), 1)
                                    .then(Mono.fromCallable(writer::finish).subscribeOn(Schedulers.boundedElastic())),
                            writer -> Mono.fromCallable(() -> { writer.close(); return true; })
                                    .subscribeOn(Schedulers.boundedElastic()));
                });
    }

    @Nonnull
    public AssetType getAssetType() {
        return PatrolAssetType.patrolPlan;
    }

    @Nonnull
    public Function<PatrolGenerationEntity, ?> getAssetIdMapper() {
        return PatrolGenerationEntity::getPlanId;
    }

    @Nonnull
    public String getAssetProperty() {
        return "planId";
    }

    /** 无分页查询和分页查询使用同一快照投影，并保留计划资产权限注入。 */
    @Override
    public reactor.core.publisher.Flux<PatrolGenerationEntity> query(
            org.hswebframework.web.api.crud.entity.QueryParamEntity query) {
        return injectQueryParam(query).flatMapMany(service::query);
    }

    public ReactiveCrudService<PatrolGenerationEntity, String> getService() {
        return service;
    }

}
