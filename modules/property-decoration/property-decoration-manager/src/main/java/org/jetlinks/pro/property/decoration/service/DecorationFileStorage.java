package org.jetlinks.pro.property.decoration.service;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.util.ReferenceCountUtil;
import org.jetlinks.core.command.Command;
import org.jetlinks.core.command.CommandSupport;
import org.jetlinks.pro.command.CommandSupportManagerProviders;
import org.jetlinks.sdk.server.SdkServices;
import org.jetlinks.sdk.server.commons.cmd.DeleteByIdCommand;
import org.jetlinks.sdk.server.file.DownloadFileCommand;
import org.jetlinks.sdk.server.file.StreamUploadFileCommand;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.NettyDataBuffer;
import org.springframework.core.io.buffer.NettyDataBufferFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 装修私有附件通过公共文件命令存取；调用前已校验业务回执和项目权限。
 * 明确选择公共文件节点，继承认证和租户上下文；未配置或缺少命令时拒绝，不退回本机磁盘。
 */
@Component
public class DecorationFileStorage {
    private static final NettyDataBufferFactory BUFFERS = new NettyDataBufferFactory(ByteBufAllocator.DEFAULT);
    private final String serverId;

    public DecorationFileStorage(@Value("${decoration.files.server-id:}") String serverId) {
        this.serverId = serverId;
    }

    public Mono<String> save(String name, Flux<DataBuffer> content) {
        var command = new StreamUploadFileCommand().withFileName(name);
        // RPC 接管 Netty 缓冲区；普通缓冲区复制后释放，取消时释放尚未转交的数据。
        command.withStream(content.map(DecorationFileStorage::transfer)
            .doOnDiscard(ByteBuf.class, ReferenceCountUtil::safeRelease));
        return support(command).flatMap(provider -> provider.execute(command).singleOrEmpty())
            .switchIfEmpty(Mono.error(DecorationRules.error("file_service_unavailable", 503)))
            .map(file -> file.getId());
    }

    public Flux<DataBuffer> read(String id) {
        var command = new DownloadFileCommand().setUrl(id);
        return support(command).flatMapMany(provider -> provider.execute(command))
            .map(BUFFERS::wrap);
    }

    public Mono<Void> delete(String id) {
        var command = DeleteByIdCommand.create(id);
        return support(command).flatMap(provider -> provider.execute(command)).then();
    }

    private Mono<CommandSupport> support(Command<?> command) {
        if (serverId.isBlank()) return Mono.error(DecorationRules.error("file_service_unavailable", 503));
        return CommandSupportManagerProviders.getCommandSupport(SdkServices.fileService)
            .as(CommandSupportManagerProviders.clusterRouter(serverId))
            .filterWhen(provider -> provider.commandIsSupported(command).defaultIfEmpty(false))
            .switchIfEmpty(Mono.error(DecorationRules.error("file_service_unavailable", 503)));
    }

    private static ByteBuf transfer(DataBuffer buffer) {
        ByteBuf result = org.jetlinks.pro.utils.ConverterUtils.convertBuffer(buffer);
        if (!(buffer instanceof NettyDataBuffer)) DataBufferUtils.release(buffer);
        return result;
    }
}
