package org.jetlinks.pro.property.decoration.integration;

import org.jetlinks.pro.command.CommandSupportManagerProviders;
import org.jetlinks.pro.property.base.command.GetPropertyProjectSealCommand;
import org.jetlinks.pro.property.decoration.model.DecorationProjectSealGateway;
import org.jetlinks.pro.property.decoration.service.DecorationRules;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** 通过基础组织公开命令读取私有项目章，沿用订阅者认证/资产上下文，不跨库、不接收客户端URL。 */
@Component
public class DirectoryProjectSealGateway implements DecorationProjectSealGateway {
    private final String serverId;
    public DirectoryProjectSealGateway(@Value("${decoration.directory.server-id:}") String serverId) { this.serverId = serverId; }
    @Override
    public Mono<Seal> read(Request request) {
        var command = new GetPropertyProjectSealCommand(); command.setProjectId(request.projectId());
        return CommandSupportManagerProviders.getCommandSupport(GetPropertyProjectSealCommand.SERVICE_ID)
            .as(CommandSupportManagerProviders.clusterRouter(serverId))
            .filterWhen(support -> support.commandIsSupported(command).defaultIfEmpty(false))
            .switchIfEmpty(Mono.error(DecorationRules.error("project_seal_unavailable", 503)))
            .flatMap(support -> support.execute(command))
            .flatMap(seal -> request.projectId().equals(seal.projectId())
                ? Mono.just(new Seal(request.applicationId(), request.tenantId(), seal.projectId(), seal.mediaType(), seal.content()))
                : Mono.error(DecorationRules.error("project_seal_mismatch", 409)));
    }
}
