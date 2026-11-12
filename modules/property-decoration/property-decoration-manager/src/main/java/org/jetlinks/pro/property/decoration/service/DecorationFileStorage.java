package org.jetlinks.pro.property.decoration.service;

import com.fasterxml.jackson.databind.JsonNode;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.util.ReferenceCountUtil;
import org.jetlinks.core.command.Command;
import org.jetlinks.core.command.CommandSupport;
import org.jetlinks.pro.command.CommandServices;
import org.jetlinks.sdk.server.SdkServices;
import org.jetlinks.sdk.server.commons.cmd.DeleteByIdCommand;
import org.jetlinks.sdk.server.file.DownloadFileCommand;
import org.jetlinks.sdk.server.file.StreamUploadFileCommand;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.NettyDataBuffer;
import org.springframework.core.io.buffer.NettyDataBufferFactory;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

import java.util.List;

/**
 * 装修私有附件通过公共文件命令存取；调用前已校验业务回执和项目权限。
 * 由平台发现公共文件服务，继承认证和租户上下文；缺少服务或命令时拒绝，不退回本机磁盘。
 * 上传、读取和补偿删除均不自动重试，避免外部操作结果未知时重复写入。
 * 显式配置公共文件网关时，上传和读取使用同一已认证入口；仅转发本次请求必要的认证头。
 */
@Component
public class DecorationFileStorage {
    private static final NettyDataBufferFactory BUFFERS = new NettyDataBufferFactory(ByteBufAllocator.DEFAULT);
    private static final String READ_AUTH = DecorationFileStorage.class.getName() + ".readAuth";
    private final WebClient readGateway;

    public DecorationFileStorage() {
        this((WebClient) null);
    }

    @Autowired
    public DecorationFileStorage(WebClient.Builder builder,
                                 @Value("${decoration.files.gateway-url:}") String gatewayUrl,
                                 @Value("${decoration.files.gateway-service-id:}") String serviceId) {
        this(gatewayUrl.isBlank() ? null : builder.clone().baseUrl(gatewayUrl)
            .defaultHeader("X-Service-Id", serviceId).build());
    }

    DecorationFileStorage(WebClient readGateway) {
        this.readGateway = readGateway;
    }

    /** 仅在当前请求的响应式上下文保留认证，不保存凭据，不转发任意客户端路由或文件地址。 */
    public static Context withRequestHeaders(Context context, HttpHeaders source) {
        var headers = new HttpHeaders();
        for (String name : List.of("X-Access-Token", "X-Asset-Scope", "X-Tenant-Domain")) {
            String value = source.getFirst(name);
            if (value != null) headers.set(name, value);
        }
        return context.put(READ_AUTH, HttpHeaders.readOnlyHttpHeaders(headers));
    }

    public Mono<String> save(String name, Flux<DataBuffer> content) {
        if (readGateway != null) {
            return Mono.deferContextual(context -> {
                HttpHeaders headers = context.getOrDefault(READ_AUTH, HttpHeaders.EMPTY);
                if (!headers.containsKey("X-Access-Token")) {
                    return Mono.error(DecorationRules.error("file_service_unavailable", 503));
                }
                var multipart = new MultipartBodyBuilder();
                multipart.asyncPart("file", content, DataBuffer.class).filename(name)
                    .contentType(MediaType.APPLICATION_OCTET_STREAM);
                // 不设置 publicAccess，不自动重试；写入和读取固定使用同一个受信公共入口。
                return readGateway.post().uri("/file/upload")
                    .headers(target -> target.addAll(headers))
                    .contentType(MediaType.MULTIPART_FORM_DATA).bodyValue(multipart.build())
                    .retrieve().bodyToMono(JsonNode.class)
                    .filter(result -> result.path("status").asInt() == 200)
                    .map(result -> result.path("result").path("id").asText())
                    .filter(id -> !id.isBlank())
                    .switchIfEmpty(Mono.error(DecorationRules.error("file_service_unavailable", 503)));
            });
        }
        var command = new StreamUploadFileCommand().withFileName(name);
        // RPC 接管 Netty 缓冲区；普通缓冲区复制后释放，取消时释放尚未转交的数据。
        command.withStream(content.map(DecorationFileStorage::transfer)
            .doOnDiscard(ByteBuf.class, ReferenceCountUtil::safeRelease));
        return support(command).flatMap(provider -> provider.execute(command).singleOrEmpty())
            .switchIfEmpty(Mono.error(DecorationRules.error("file_service_unavailable", 503)))
            .map(file -> file.getId());
    }

    public Flux<DataBuffer> read(String id) {
        if (readGateway != null) {
            // 文件ID仅取自有权业务回执；URI片段编码防止其改变可信网关和下载路径。
            return Flux.deferContextual(context -> {
                HttpHeaders headers = context.getOrDefault(READ_AUTH, HttpHeaders.EMPTY);
                if (!headers.containsKey("X-Access-Token")) {
                    return Flux.error(DecorationRules.error("file_service_unavailable", 503));
                }
                return readGateway.get().uri(builder -> builder.path("/file/{id}").build(id))
                    .headers(target -> target.addAll(headers))
                    .retrieve().bodyToFlux(DataBuffer.class);
            });
        }
        var command = new DownloadFileCommand().setUrl(id);
        return support(command).flatMapMany(provider -> provider.execute(command))
            .map(BUFFERS::wrap);
    }

    public Mono<Void> delete(String id) {
        var command = DeleteByIdCommand.create(id);
        return support(command).flatMap(provider -> provider.execute(command)).then();
    }

    private Mono<CommandSupport> support(Command<?> command) {
        // 与巡更使用相同的服务发现入口，不将文件能力绑定到某个部署实例名称。
        return CommandServices.getReactive(SdkServices.fileService)
            .filterWhen(provider -> provider.commandIsSupported(command).defaultIfEmpty(false))
            .switchIfEmpty(Mono.error(DecorationRules.error("file_service_unavailable", 503)));
    }

    private static ByteBuf transfer(DataBuffer buffer) {
        ByteBuf result = org.jetlinks.pro.utils.ConverterUtils.convertBuffer(buffer);
        if (!(buffer instanceof NettyDataBuffer)) DataBufferUtils.release(buffer);
        return result;
    }
}
