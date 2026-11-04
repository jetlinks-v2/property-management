package org.jetlinks.pro.patrol.integration;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.util.ReferenceCountUtil;
import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.core.command.CommandSupport;
import org.jetlinks.pro.command.CommandServices;
import org.jetlinks.sdk.server.SdkServices;
import org.jetlinks.sdk.server.file.DownloadFileCommand;
import org.jetlinks.sdk.server.file.FileInfo;
import org.jetlinks.sdk.server.file.UploadFileCommand;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.io.ByteArrayOutputStream;
import java.util.function.Supplier;

/** 通过平台已提供的文件命令服务传输图片，不依赖其他物业系统或接收客户端下载 URL。 */
@Component
public class PlatformPatrolFiles {
    public static final int MAX_IMAGE_BYTES = 5 * 1024 * 1024;
    private final Supplier<Mono<CommandSupport>> support;

    @Autowired
    public PlatformPatrolFiles() {
        this(() -> CommandServices.getReactive(SdkServices.fileService));
    }

    PlatformPatrolFiles(Supplier<Mono<CommandSupport>> support) { this.support = support; }

    public Mono<FileInfo> upload(String name, String mediaType, byte[] content) {
        return support.get()
                .switchIfEmpty(Mono.error(new BusinessException("error.patrol.file.unavailable")))
                .flatMap(commands -> Mono.defer(() -> {
                    // 使用无池的字节数组视图；文件命令负责消费，取消或错误时也不占用池化内存。
                    var command = new UploadFileCommand().withFileName(name).withContentType(mediaType)
                            .withContentLength(content.length).withContent(Unpooled.wrappedBuffer(content));
                    return commands.execute(command)
                            .filter(file -> file.getId() != null && !file.getId().isBlank()
                                    && file.getAccessUrl() != null && !file.getAccessUrl().isBlank());
                }))
                .switchIfEmpty(Mono.error(new BusinessException("error.patrol.file.unavailable")));
    }

    public Mono<byte[]> read(String internalUrl) {
        return support.get()
                .switchIfEmpty(Mono.error(new BusinessException("error.patrol.file.unavailable")))
                .flatMap(commands -> commands.execute(new DownloadFileCommand().setUrl(internalUrl))
                        .collect(ByteArrayOutputStream::new, (output, buffer) -> append(output, buffer))
                        .filter(output -> output.size() > 0)
                        .switchIfEmpty(Mono.error(new BusinessException("error.patrol.file.unavailable")))
                        .map(ByteArrayOutputStream::toByteArray)
                        .doOnDiscard(ByteBuf.class, ReferenceCountUtil::safeRelease));
    }

    private static void append(ByteArrayOutputStream output, ByteBuf buffer) {
        try {
            if ((long) output.size() + buffer.readableBytes() > MAX_IMAGE_BYTES)
                throw new BusinessException("error.patrol.file.size");
            output.writeBytes(ByteBufUtil.getBytes(buffer));
        } finally {
            ReferenceCountUtil.safeRelease(buffer);
        }
    }
}
