package org.jetlinks.pro.smartpark.repair.service;

import lombok.AllArgsConstructor;
import org.hswebframework.web.exception.NotFoundException;
import org.jetlinks.pro.assets.AssetType;
import org.jetlinks.pro.assets.AssetsHolder;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.entity.PropertySpaceEntity;
import org.jetlinks.pro.property.service.PropertySpaceService;
import org.jetlinks.pro.smartpark.repair.asset.RepairAssetType;
import org.jetlinks.pro.smartpark.repair.entity.RepairConfigurationEntity;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 发布二维码时由配置人员明确委托项目、渠道、分类与空间范围；不允许跨应用引用。 */
@Service @AllArgsConstructor
public class RepairQrConfigurationService {
    private final RepairConfigurationService configurations;
    private final PropertySpaceService spaces;

    public Mono<Void> validate(RepairConfigurationEntity input, RepairContext context) {
        if (!"qr".equals(input.getKind())) return Mono.empty();
        Map<String,Object> data = input.getConfiguration();
        Mono<Void> unique = configurations.createQuery().where(RepairConfigurationEntity::getQrCode,input.getQrCode())
            .fetch().doOnNext(other -> RepairFlowRules.require(Objects.equals(other.getId(),input.getId()), "qr_code_duplicate")).then();
        if (!Boolean.TRUE.equals(input.getEnabled())) return unique;
        List<String> roots = RepairQrRules.ids(data,"spaceRootIds");
        var referencedSpaces = new HashSet<>(roots);
        referencedSpaces.addAll(RepairQrRules.ids(data,"publicAreaRootIds"));
        RepairQrRules.areas(data).forEach(area -> referencedSpaces.addAll(RepairQrRules.ids(area,"spaceRootIds")));
        return unique.then(reference(Objects.toString(data.get("channelId")),"channel",input,context))
            .thenMany(Flux.fromIterable(RepairQrRules.ids(data,"categoryIds"))
                .concatMap(id -> reference(id,"category",input,context))).then()
            .then(AssetsHolder.assertPermission(AssetType.of("space"),referencedSpaces,CrudAssetPermission.read))
            .thenMany(Flux.fromIterable(referencedSpaces).concatMap(id -> spaceUnderRoot(id,roots,input,context,0)))
            .then();
    }

    private Mono<Void> reference(String id,String kind,RepairConfigurationEntity input,RepairContext ctx) {
        return AssetsHolder.assertPermission(RepairAssetType.repairConfiguration,id,CrudAssetPermission.read)
            .then(configurations.findById(id)).switchIfEmpty(Mono.error(new NotFoundException()))
            .doOnNext(row -> RepairFlowRules.require(kind.equals(row.getKind()) && Boolean.TRUE.equals(row.getEnabled())
                && Objects.equals(row.getApplicationId(),ctx.applicationId())
                && Objects.equals(row.getExtension("tenantId"),ctx.tenantId())
                && Objects.equals(row.getBusinessProjectId(),input.getBusinessProjectId()),"configuration_project_mismatch")).then();
    }

    private Mono<Void> spaceUnderRoot(String id,List<String> roots,RepairConfigurationEntity input,RepairContext ctx,int depth) {
        RepairFlowRules.require(depth < 64,"qr_space_not_allowed");
        return spaces.findById(id).switchIfEmpty(Mono.error(new NotFoundException())).flatMap(space -> {
            RepairFlowRules.require(Boolean.TRUE.equals(space.getSourceEnabled())
                && Objects.equals(space.getTenantId(),ctx.tenantId())
                && Objects.equals(space.getBusinessProjectId(),input.getBusinessProjectId()),"qr_space_not_allowed");
            if (roots.contains(id)) return Mono.empty();
            RepairFlowRules.require(space.getParentId()!=null,"qr_space_not_allowed");
            return spaceUnderRoot(space.getParentId(),roots,input,ctx,depth+1);
        });
    }

    static boolean references(RepairConfigurationEntity row,String id) {
        if (!"qr".equals(row.getKind()) || row.getConfiguration()==null) return false;
        return Objects.equals(row.getConfiguration().get("channelId"),id)
            || RepairQrRules.ids(row.getConfiguration(),"categoryIds").contains(id);
    }
}
