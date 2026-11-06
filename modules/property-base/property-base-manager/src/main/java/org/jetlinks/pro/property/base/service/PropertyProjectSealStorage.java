package org.jetlinks.pro.property.base.service;

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
import org.springframework.core.io.buffer.NettyDataBufferFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** 项目章使用固定公共文件节点，保留认证及租户上下文；不接受任意下载URL、不回退本机文件。 */
@Component
public class PropertyProjectSealStorage {
    private static final NettyDataBufferFactory BUFFERS = new NettyDataBufferFactory(ByteBufAllocator.DEFAULT);
    private final String serverId;
    public PropertyProjectSealStorage(@Value("${property.files.server-id:}") String serverId) { this.serverId = serverId; }
    public Mono<String> save(String name, byte[] bytes) {
        var command = new StreamUploadFileCommand().withFileName(name);
        command.withStream(Mono.fromSupplier(() -> ByteBufAllocator.DEFAULT.buffer(bytes.length).writeBytes(bytes)).flux()
            .doOnDiscard(ByteBuf.class, ReferenceCountUtil::safeRelease));
        return support(command).flatMap(provider -> provider.execute(command).singleOrEmpty())
            .switchIfEmpty(Mono.error(ArchiveScope.error("seal_storage_unavailable", 503))).map(file -> file.getId());
    }
    public Mono<byte[]> read(String fileId) {
        var command = new DownloadFileCommand().setUrl(fileId);
        return support(command).flatMap(provider -> DataBufferUtils.join(provider.execute(command)
            .map(BUFFERS::wrap), PropertyProjectSealService.MAX_BYTES)).map(PropertyProjectSealStorage::bytes);
    }
    public Mono<Void> delete(String fileId) {
        var command = DeleteByIdCommand.create(fileId);
        return support(command).flatMap(provider -> provider.execute(command)).then();
    }
    private Mono<CommandSupport> support(Command<?> command) {
        if (serverId.isBlank()) return Mono.error(ArchiveScope.error("seal_storage_unavailable", 503));
        return CommandSupportManagerProviders.getCommandSupport(SdkServices.fileService)
            .as(CommandSupportManagerProviders.clusterRouter(serverId))
            .filterWhen(provider -> provider.commandIsSupported(command).defaultIfEmpty(false))
            .switchIfEmpty(Mono.error(ArchiveScope.error("seal_storage_unavailable", 503)));
    }
    static byte[] bytes(DataBuffer buffer) {
        try { byte[] bytes = new byte[buffer.readableByteCount()]; buffer.read(bytes); return bytes; }
        finally { DataBufferUtils.release(buffer); }
    }
}
