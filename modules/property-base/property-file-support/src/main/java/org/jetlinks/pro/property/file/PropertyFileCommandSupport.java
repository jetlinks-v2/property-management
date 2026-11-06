package org.jetlinks.pro.property.file;

import lombok.RequiredArgsConstructor;
import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.authorization.annotation.Authorize;
import org.hswebframework.web.authorization.exception.AccessDenyException;
import org.hswebframework.web.exception.NotFoundException;
import org.jetlinks.core.annotation.command.CommandHandler;
import org.jetlinks.pro.annotation.command.CommandService;
import org.jetlinks.pro.assets.AssetManager;
import org.jetlinks.pro.assets.AssetType;
import org.jetlinks.pro.assets.AssetsHolder;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.io.file.FileManager;
import org.jetlinks.pro.property.base.command.AssertPropertyFilesCommand;
import org.jetlinks.pro.tenant.assets.TenantAssetsHolder;
import org.jetlinks.pro.tenant.holder.TenantHolder;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.util.List;

/**
 * 仅部署在 Cloud 文件所有者进程。业务服务不能用本地空文件库进行授权或存在性判断。
 * 文件资产授权与显式租户取交集，命令上下文沿平台 RPC 传播，无系统身份或数据库共享。
 */
@Component
@Authorize
@RequiredArgsConstructor
@CommandService(id = AssertPropertyFilesCommand.SERVICE_ID, name = "物业附件校验")
public class PropertyFileCommandSupport {
    private final FileManager files;
    private final AssetManager assets;

    @CommandHandler(name = "校验物业附件引用")
    public Mono<Boolean> assertFiles(AssertPropertyFilesCommand command) {
        if (command.getFileIds().size() > 1000) {
            return Mono.error(new IllegalArgumentException("fileIds exceeds 1000"));
        }
        return Authentication.currentReactive()
            .switchIfEmpty(Mono.error(new AccessDenyException()))
            .then(TenantHolder.currentId().switchIfEmpty(Mono.error(new AccessDenyException())))
            .flatMap(tenant -> Flux.fromIterable(command.getFileIds()).distinct()
                .concatMap(id -> new TenantAssetsHolder(List.of(tenant), assets)
                    .assertPermission0(AssetType.of("file"), List.of(id), CrudAssetPermission.read)
                    .then(AssetsHolder.assertPermission(AssetType.of("file"), id, CrudAssetPermission.read))
                    .then(files.getFile(id).switchIfEmpty(Mono.error(new NotFoundException()))))
                .then(Mono.just(true)));
    }
}
