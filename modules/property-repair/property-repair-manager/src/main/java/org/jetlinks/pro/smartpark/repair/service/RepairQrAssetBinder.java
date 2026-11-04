package org.jetlinks.pro.smartpark.repair.service;

import lombok.AllArgsConstructor;
import org.jetlinks.pro.assets.AssetBindManager;
import org.jetlinks.pro.assets.AssetBindRequest;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.util.List;

/** 服务端能力创建的资产绑定到已验证的真实租户、应用、项目组织，不绑定虚构的匿名员工。 */
@Component @AllArgsConstructor
public class RepairQrAssetBinder {
    private final AssetBindManager bindings;
    Mono<Void> bind(String assetType,String assetId,RepairQrGrantProvider.Grant grant) {
        RepairContext ctx=grant.context();
        return bindings.bindAssets(Flux.just(
            request("tenant",ctx.tenantId(),assetType,assetId),
            request("business_application",ctx.applicationId(),assetType,assetId),
            request("org",grant.project.getId(),assetType,assetId)));
    }
    private AssetBindRequest request(String type,String target,String assetType,String id) {
        return new AssetBindRequest(type,target,assetType,List.of(id),List.of(),true,null);
    }
}
