package org.jetlinks.pro.property.decoration.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetlinks.core.trace.MonoTracer;
import org.jetlinks.pro.property.decoration.entity.DecorationConfigurationImportEntity;
import org.jetlinks.pro.property.decoration.excel.DecorationCheckImportPlan;
import org.jetlinks.pro.property.decoration.excel.DecorationCheckWorkbook;
import org.jetlinks.pro.property.decoration.excel.DecorationImportImagePart;
import org.jetlinks.pro.property.decoration.model.DecorationConfigurationImports;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.jetlinks.pro.property.decoration.service.DecorationRules.error;
import static org.jetlinks.pro.property.decoration.service.DecorationRules.require;

/** 文件校验、公开目录和图片编排；配置+成功收据由独立响应式事务提交。 */
@Service @RequiredArgsConstructor @Slf4j
public class DecorationConfigurationImportService {
    private final DecorationScope scope;
    private final DecorationConfigurationImportRepository imports;
    private final DecorationConfigurationImportWriter writer;
    private final DecorationAttachmentService attachments;
    private record Upload(String digest, DecorationCheckWorkbook.Parsed parsed) {}

    public Mono<byte[]> template() {
        return scope.current().flatMap(ctx -> scope.employee().then(Mono.fromCallable(DecorationCheckWorkbook::template).subscribeOn(Schedulers.boundedElastic())));
    }
    public Mono<DecorationConfigurationImports.Result> result(String requestId) {
        return scope.current().flatMap(ctx -> scope.employee().then(load(key(ctx, requestId), ctx))
            .switchIfEmpty(Mono.error(error("request_not_found", 404))).map(DecorationConfigurationImportEntity::getResult));
    }
    public Mono<DecorationConfigurationImports.Result> importChecks(String requestId, FilePart file) {
        if (!file.filename().toLowerCase(Locale.ROOT).endsWith(".xlsx")) return Mono.error(error("import_file", 400));
        return scope.current().flatMap(ctx -> scope.employee().then(read(file)).flatMap(upload -> {
            String key = key(ctx, requestId);
            return load(key, ctx).map(receipt -> {
                require(upload.digest().equals(receipt.getFileDigest()), "request_conflict"); return receipt.getResult();
            }).switchIfEmpty(Mono.defer(() -> Mono.zip(scope.projects().collectList(), scope.dictionaryItems("house-type").collectList())
                .map(directory -> DecorationCheckImportPlan.resolve(upload.parsed(), directory.getT1(), directory.getT2()))
                .flatMap(plan -> plan.errors().isEmpty() ? prepare(key, upload.digest(), plan, ctx) : Mono.just(validation(plan.totalCount(), plan.validCount(), plan.errors())))));
        })).as(MonoTracer.create("/property/decoration/configuration-import"));
    }
    private Mono<Upload> read(FilePart file) {
        return DataBufferUtils.join(file.content(), DecorationCheckWorkbook.MAX_BYTES)
            .switchIfEmpty(Mono.error(error("import_file", 400)))
            .map(buffer -> {
                try { var bytes = new byte[buffer.readableByteCount()]; buffer.read(bytes); return bytes; }
                finally { DataBufferUtils.release(buffer); }
            }).flatMap(bytes -> Mono.fromCallable(() -> new Upload(digest(bytes), DecorationCheckWorkbook.read(bytes))).subscribeOn(Schedulers.boundedElastic()))
            .onErrorMap(DataBufferLimitException.class, e -> error("import_file", 400));
    }
    private Mono<DecorationConfigurationImports.Result> prepare(String key, String digest, DecorationCheckImportPlan.Plan plan, DecorationScope.Context ctx) {
        return writer.prepare(plan, ctx).flatMap(prepared -> {
            var errors = new ArrayList<DecorationCheckWorkbook.Problem>();
            for (var item : prepared) {
                if (item.input().getContent().getChecks().size() > 200) errors.add(new DecorationCheckWorkbook.Problem(
                    item.group().rows().get(0).rowNumber(), DecorationCheckWorkbook.HEADERS.get(6), "", "check_count"));
            }
            if (!errors.isEmpty()) return Mono.just(validation(plan.totalCount(), plan.validCount() - errors.size(), errors));
            prepared.forEach(item -> DecorationConfigurationService.validate(item.input()));
            return perform(key, digest, plan, prepared, ctx);
        });
    }
    private Mono<DecorationConfigurationImports.Result> perform(String key, String digest, DecorationCheckImportPlan.Plan plan,
        List<DecorationConfigurationImportWriter.Prepared> prepared, DecorationScope.Context ctx) {
        // 开始外部图片/写库后，HTTP取消不取消这次有限操作。原请求号可核对最终结果，不自动重试上传或提交。
        return Mono.defer(() -> {
            var uploaded = new ArrayList<String>();
            return Flux.fromIterable(prepared).concatMap(item -> Flux.fromIterable(item.group().rows()).filter(row -> row.image() != null)
                .concatMap(row -> attachments.upload(item.input().getProjectId(), null, "template", new DecorationImportImagePart(row.image()))
                    .doOnNext(receipt -> {
                        uploaded.add(receipt.id());
                        item.input().getContent().getChecks().stream().filter(check -> check.getCategory().equals(row.item().getCategory()) && check.getName().equals(row.item().getName()))
                            .findFirst().orElseThrow().setExampleFileId(receipt.id());
                    })))
                .then(Mono.defer(() -> writer.write(key, digest, plan, prepared, uploaded, ctx))
                    .onErrorResume(failure -> recoverWrite(key, digest, ctx, failure)))
                .flatMap(result -> cleanup(key, uploaded, ctx).thenReturn(result))
                .onErrorResume(primary -> cleanup(key, uploaded, ctx).onErrorResume(cleanup -> {
                    primary.addSuppressed(cleanup); log.warn("Decoration import {} temporary file cleanup failed", key); return Mono.empty();
                }).then(Mono.error(primary)));
        }).cache();
    }
    /** writer事务终止后核对同键赢家；同文件竞争复用成功收据，其他配置唯一键竞争返回版本冲突。 */
    private Mono<DecorationConfigurationImports.Result> recoverWrite(String key, String digest, DecorationScope.Context ctx, Throwable failure) {
        var fallback = failure instanceof org.hswebframework.ezorm.rdb.exception.DuplicateKeyException
            || failure instanceof org.springframework.dao.DuplicateKeyException ? error("version_conflict", 409) : failure;
        return load(key, ctx).map(receipt -> {
            require(digest.equals(receipt.getFileDigest()), "request_conflict"); return receipt.getResult();
        }).switchIfEmpty(Mono.error(fallback));
    }
    private Mono<Void> cleanup(String key, List<String> uploaded, DecorationScope.Context ctx) {
        if (uploaded.isEmpty()) return Mono.empty();
        // 先核对成功收据，兼顾并发同键及提交响应丢失；不得删除任何已提交配置使用的图片。
        return imports.findById(key).map(receipt -> receipt.getResult().attachmentIds()).defaultIfEmpty(List.of())
            .flatMap(retained -> attachments.discardImportTemplates(uploaded.stream().filter(id -> !retained.contains(id)).toList(), ctx));
    }
    private Mono<DecorationConfigurationImportEntity> load(String key, DecorationScope.Context ctx) {
        return imports.findById(key).filter(receipt -> ctx.applicationId().equals(receipt.getApplicationId())
                && ctx.tenantId().equals(receipt.getTenantId()) && ctx.userId().equals(receipt.getActorId()))
            .flatMap(receipt -> Flux.fromIterable(receipt.getResult().configurations()).map(DecorationConfigurationImports.SavedConfiguration::projectId).distinct()
                .concatMap(scope::project).then(Mono.just(receipt)));
    }
    private static DecorationConfigurationImports.Result validation(int total, int valid, List<DecorationCheckWorkbook.Problem> errors) {
        return new DecorationConfigurationImports.Result(total, valid, 0, errors, List.of(), List.of());
    }
    static String key(DecorationScope.Context ctx, String requestId) {
        return DecorationApplicationService.identity(ctx.tenantId() + ":" + ctx.applicationId() + ":" + ctx.userId() + ":" + requestId);
    }
    private String digest(byte[] bytes) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
