package org.jetlinks.pro.property.decoration.service;

import lombok.RequiredArgsConstructor;
import org.jetlinks.pro.property.decoration.entity.DecorationConfigurationImportEntity;
import org.jetlinks.pro.property.decoration.excel.DecorationCheckImportPlan;
import org.jetlinks.pro.property.decoration.model.DecorationConfigurationImports;
import org.jetlinks.pro.property.decoration.model.DecorationModels;
import org.jetlinks.pro.property.decoration.model.DecorationRequests;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.util.List;

import static org.jetlinks.pro.property.decoration.service.DecorationRules.require;

/** 先捕获全部预期版本，再整批提交；每份配置仍经标准保存边界重验项目、附件和CAS。 */
@Service @RequiredArgsConstructor
public class DecorationConfigurationImportWriter {
    private final DecorationConfigurationRepository configurations;
    private final DecorationConfigurationService service;
    private final DecorationConfigurationImportRepository imports;

    public record Prepared(DecorationCheckImportPlan.Group group, DecorationRequests.Config input) {}

    public Mono<List<Prepared>> prepare(DecorationCheckImportPlan.Plan plan, DecorationScope.Context ctx) {
        return Flux.fromIterable(plan.groups()).concatMap(group -> {
            var dimension = group.dimension();
            String id = DecorationConfigurationService.key(ctx.applicationId(), dimension.projectId(), "checks", dimension.houseType(), dimension.decorationType(), dimension.scene());
            return configurations.findById(id).map(old -> {
                require(ctx.tenantId().equals(old.getTenantId()), "version_conflict");
                var input = input(group); input.setId(old.getId()); input.setName(old.getName()); input.setVersion(old.getVersion());
                input.getContent().setChecks(DecorationCheckImportPlan.merge(old.getContent().getChecks(), group, ignored -> null));
                return new Prepared(group, input);
            }).switchIfEmpty(Mono.fromSupplier(() -> {
                var input = input(group); input.getContent().setChecks(DecorationCheckImportPlan.merge(List.of(), group, ignored -> null));
                return new Prepared(group, input);
            }));
        }).collectList();
    }
    private DecorationRequests.Config input(DecorationCheckImportPlan.Group group) {
        var dimension = group.dimension(); var input = new DecorationRequests.Config();
        input.setProjectId(dimension.projectId()); input.setKind("checks"); input.setHouseType(dimension.houseType());
        input.setDecorationType(dimension.decorationType()); input.setScene(dimension.scene()); input.setName("检查配置导入");
        input.setContent(new DecorationModels.Configuration()); return input;
    }

    @Transactional
    public Mono<DecorationConfigurationImports.Result> write(String key, String digest, DecorationCheckImportPlan.Plan plan,
        List<Prepared> prepared, List<String> attachmentIds, DecorationScope.Context ctx) {
        return imports.findById(key).flatMap(existing -> {
            require(digest.equals(existing.getFileDigest()), "request_conflict"); return Mono.just(existing.getResult());
        }).switchIfEmpty(Mono.defer(() -> Flux.fromIterable(prepared).concatMap(item -> service.save(item.input()))
            .map(saved -> new DecorationConfigurationImports.SavedConfiguration(saved.getId(), saved.getProjectId(), saved.getVersion())).collectList()
            .flatMap(saved -> {
                var result = new DecorationConfigurationImports.Result(plan.totalCount(), plan.validCount(), plan.totalCount(), List.of(), saved, List.copyOf(attachmentIds));
                var receipt = new DecorationConfigurationImportEntity(); receipt.setId(key); receipt.setApplicationId(ctx.applicationId());
                receipt.setTenantId(ctx.tenantId()); receipt.setActorId(ctx.userId()); receipt.setFileDigest(digest);
                receipt.setCreateTime(System.currentTimeMillis()); receipt.setResult(result);
                return imports.insert(receipt).thenReturn(result);
            })));
    }
}
