package org.jetlinks.pro.property.decoration.service;

import lombok.RequiredArgsConstructor;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.crud.query.QueryHelper;
import org.jetlinks.pro.property.decoration.entity.DecorationConfigurationEntity;
import org.jetlinks.pro.property.decoration.model.DecorationRequests;
import org.jetlinks.pro.property.decoration.model.DecorationModels;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import java.util.Set;
import static org.jetlinks.pro.property.decoration.service.DecorationRules.*;

/** 配置版本与匹配维度明确；同维度唯一，申请读取后保存完整快照。 */
@Service
@RequiredArgsConstructor
public class DecorationConfigurationService {
    private final DecorationScope scope;
    private final DecorationConfigurationRepository repository;
    private final DecorationAttachmentService attachments;

    public Mono<PagerResult<DecorationConfigurationEntity>> query(QueryParamEntity input) {
        return scope.current().flatMap(ctx -> scope.query(input, ctx)).map(q -> q.toNestQuery(n -> n.and("deleted", false)).getParam())
            .flatMap(q -> QueryHelper.queryPager(q, repository::createQuery));
    }
    public Mono<DecorationConfigurationEntity> match(DecorationScope.Context ctx, String project, String kind, String house, String type, String scene) {
        String key = key(ctx.applicationId(), project, kind, house, type, scene);
        return scope.project(project).then(repository.findById(key))
            .filter(row -> !row.getDeleted() && row.getEnabled() && row.getTenantId().equals(ctx.tenantId()))
            .switchIfEmpty(Mono.error(error("configuration_missing", 409)));
    }
    /** 原系统未配置备案有效期时默认3个月；仅缺配置回落，认证/远程故障不吞掉。 */
    public Mono<Integer> validity(DecorationScope.Context ctx, String project) {
        return scope.project(project).then(repository.findById(key(ctx.applicationId(), project, "validity", "", "", "")))
            .filter(r -> !r.getDeleted() && r.getEnabled() && r.getTenantId().equals(ctx.tenantId()))
            .map(r -> r.getContent().getValidityMonths()).defaultIfEmpty(3);
    }
    @Transactional
    public Mono<DecorationConfigurationEntity> save(DecorationRequests.Config input) {
        validate(input);
        return scope.current().flatMap(ctx -> scope.employee().then(scope.project(input.getProjectId()))
            .then(attachments.validate(templateIds(input.getContent()), input.getProjectId(), null, ctx, true))
            .then(Mono.defer(() -> {
                String id = key(ctx.applicationId(), input.getProjectId(), input.getKind(), input.getHouseType(), input.getDecorationType(), input.getScene());
                require(input.getId() == null || id.equals(input.getId()), "configuration_dimensions_immutable");
                var row = new DecorationConfigurationEntity(); row.setId(id); row.setConfigurationKey(id);
                row.setApplicationId(ctx.applicationId()); row.setTenantId(ctx.tenantId()); row.setProjectId(input.getProjectId());
                row.setKind(input.getKind()); row.setHouseType(input.getHouseType()); row.setDecorationType(input.getDecorationType()); row.setScene(input.getScene());
                row.setName(input.getName()); row.setEnabled(input.isEnabled()); row.setDeleted(false); row.setContent(input.getContent());
                row.setVersion(input.getVersion() + 1); row.setModifyTime(System.currentTimeMillis());
                return repository.findById(id).flatMap(old -> {
                    require(old.getTenantId().equals(ctx.tenantId()) && old.getVersion() == input.getVersion(), "version_conflict");
                    row.setCreateTime(old.getCreateTime());
                    return repository.createUpdate().set(row).where("id", id).and("version", input.getVersion()).execute()
                        .doOnNext(n -> require(n == 1, "version_conflict")).thenReturn(row);
                }).switchIfEmpty(Mono.defer(() -> {
                    require(input.getVersion() == 0, "version_conflict"); row.setCreateTime(row.getModifyTime());
                    return repository.insert(row).thenReturn(row);
                }));
            })));
    }
    @Transactional
    public Mono<Void> delete(String id, long version) {
        return query(QueryParamEntity.newQuery().where("id", id).getParam()).flatMap(page -> {
            require(page.getData().size() == 1, "configuration_missing");
            return scope.employee().then(repository.createUpdate().set("deleted", true).set("version", version + 1)
                .where("id", id).and("version", version).execute().doOnNext(n -> require(n == 1, "version_conflict"))).then();
        });
    }
    static String key(String app, String project, String kind, String house, String type, String scene) {
        return UUID.nameUUIDFromBytes(String.join("|", app, project, kind, house, type, scene).getBytes(StandardCharsets.UTF_8)).toString();
    }
    private static java.util.List<String> templateIds(DecorationModels.Configuration content) {
        return java.util.stream.Stream.concat(content.getMaterials().stream().map(DecorationModels.MaterialDefinition::getTemplateFileId),
            content.getChecks().stream().map(DecorationModels.CheckItem::getExampleFileId)).filter(Objects::nonNull).toList();
    }
    static void validate(DecorationRequests.Config input) {
        var content = input.getContent(); require(content.getMaterials().size() <= 200 && content.getChecks().size() <= 200, "too_many");
        require(java.util.Set.of("agreement", "materials", "checks", "validity").contains(input.getKind()), "invalid_configuration");
        require(input.getHouseType() != null && input.getDecorationType() != null && input.getScene() != null, "invalid_configuration");
        if (Set.of("agreement", "materials", "checks").contains(input.getKind())) require(!input.getHouseType().isBlank(), "invalid_configuration");
        if (Set.of("materials", "checks").contains(input.getKind())) require(Set.of("first", "second").contains(input.getDecorationType()), "invalid_configuration");
        if ("checks".equals(input.getKind())) require(Set.of("daily", "firstAcceptance", "secondAcceptance").contains(input.getScene()), "invalid_configuration");
        if ("agreement".equals(input.getKind())) {
            require(content.getAgreement() != null, "invalid_configuration");
            var agreement = content.getAgreement(); amount(agreement.getDeposit());
            require(agreement.getTitle() != null && !agreement.getTitle().isBlank() && agreement.getContent() != null && !agreement.getContent().isBlank()
                && agreement.getChargeItemId() != null && !agreement.getChargeItemId().isBlank() && agreement.getChargeItemName() != null && !agreement.getChargeItemName().isBlank(), "invalid_configuration");
            require((agreement.getFirstRefund() == null || agreement.getFirstRefund().signum() >= 0)
                && (agreement.getSecondRefund() == null || agreement.getSecondRefund().signum() >= 0)
                && agreement.getFees().values().stream().allMatch(v -> v != null && v.signum() >= 0), "invalid_amount");
        }
        if ("agreement".equals(input.getKind())) {
            var agreement = content.getAgreement();
            var first = agreement.getFirstRefund() == null ? java.math.BigDecimal.ZERO : agreement.getFirstRefund();
            var second = agreement.getSecondRefund() == null ? java.math.BigDecimal.ZERO : agreement.getSecondRefund();
            require(first.add(second).compareTo(agreement.getDeposit()) <= 0 && first.scale() <= 2 && second.scale() <= 2, "invalid_refund_amount");
            var total = agreement.getRefundAmount();
            require(total == null || total.signum() >= 0 && total.compareTo(agreement.getDeposit()) <= 0 && total.scale() <= 2, "invalid_refund_amount");
        }
        if ("validity".equals(input.getKind())) require(content.getValidityMonths() != null && content.getValidityMonths() > 0 && content.getValidityMonths() <= 120, "invalid_configuration");
        require(content.getMaterials().stream().map(DecorationModels.MaterialDefinition::getId).distinct().count() == content.getMaterials().size(), "invalid_configuration");
        for (var item : content.getMaterials()) require(item.getId() != null && item.getName() != null && Set.of("material","drawing").contains(item.getKind()) && Set.of("upload","sign").contains(item.getSource()), "invalid_configuration");
        require(content.getChecks().stream().map(DecorationModels.CheckItem::getId).distinct().count() == content.getChecks().size(), "invalid_configuration");
        for (var item : content.getChecks()) {
            require(item.getId() != null && !item.getId().isBlank() && item.getName() != null && !item.getName().isBlank()
                && Set.of("text","number","option","image").contains(item.getInputType()), "invalid_configuration");
            require(item.getMin() == null || item.getMax() == null || item.getMin().compareTo(item.getMax()) <= 0, "invalid_configuration");
            require(item.getCategorySort() == null || item.getCategorySort() > 0, "invalid_configuration");
            require(!"option".equals(item.getInputType()) || !item.getOptions().isEmpty(), "invalid_configuration");
        }
        content.getMaterials().sort(java.util.Comparator.comparingInt(DecorationModels.MaterialDefinition::getSort));
        sortChecks(content.getChecks());
    }
}
