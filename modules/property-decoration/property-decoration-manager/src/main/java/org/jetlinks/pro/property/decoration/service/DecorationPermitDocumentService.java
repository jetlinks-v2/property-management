package org.jetlinks.pro.property.decoration.service;

import lombok.RequiredArgsConstructor;
import org.jetlinks.pro.property.decoration.entity.DecorationApplicationEntity;
import org.jetlinks.pro.property.decoration.model.DecorationProjectSealGateway;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;

import static org.jetlinks.pro.property.decoration.service.DecorationRules.*;

/** 打印和预览共用申请范围及项目章校验；不返回公开文件URL、不持久化印章主档。 */
@Service
@RequiredArgsConstructor
public class DecorationPermitDocumentService {
    private final DecorationApplicationService applications;
    private final DecorationScope scope;
    private final ObjectProvider<DecorationProjectSealGateway> seals;

    public record Document(DecorationApplicationEntity application, String sealDataUrl, String sealDigest) {}

    public Mono<Document> prepare(String id) {
        return applications.read(id, false).flatMap(row -> scope.current().flatMap(ctx -> scope.employee()
            .then(resolve(row, ctx))));
    }

    /** 已完成聚合范围/行锁校验的打印动作复用此方法，缺章不能留下领取状态或轨迹。 */
    Mono<Document> resolve(DecorationApplicationEntity row, DecorationScope.Context ctx) {
        paid(row);
        require(row.getPermit() != null, "permit_required");
        return Mono.defer(() -> {
            var gateway = seals.getIfAvailable();
            if (gateway == null) return Mono.error(error("project_seal_unavailable", 503));
            var request = new DecorationProjectSealGateway.Request(ctx.applicationId(), ctx.tenantId(), row.getProjectId());
            return gateway.read(request).switchIfEmpty(Mono.error(error("project_seal_required", 409)))
                .publishOn(reactor.core.scheduler.Schedulers.boundedElastic()).map(seal -> {
                require(Objects.equals(seal.applicationId(), request.applicationId())
                    && Objects.equals(seal.tenantId(), request.tenantId())
                    && Objects.equals(seal.projectId(), request.projectId()), "project_seal_mismatch");
                byte[] image = seal.content();
                require(image != null && image.length > 8 && image.length <= 2 * 1024 * 1024, "project_seal_invalid");
                boolean png = "image/png".equals(seal.mediaType()) && image[0] == (byte) 0x89 && image[1] == 0x50
                    && image[2] == 0x4e && image[3] == 0x47 && image[4] == 13 && image[5] == 10 && image[6] == 26 && image[7] == 10;
                boolean jpeg = "image/jpeg".equals(seal.mediaType()) && image[0] == (byte) 0xff
                    && image[1] == (byte) 0xd8 && image[2] == (byte) 0xff;
                require(png || jpeg, "project_seal_invalid");
                validateImage(image);
                try {
                    String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(image));
                    return new Document(row, "data:" + seal.mediaType() + ";base64," + Base64.getEncoder().encodeToString(image), digest);
                } catch (java.security.NoSuchAlgorithmException impossible) {
                    throw new IllegalStateException(impossible);
                }
            });
        });
    }

    /** 先读尺寸再解码，避免小压缩文件声明超大像素；ImageIO和摘要运算在boundedElastic执行。 */
    private static void validateImage(byte[] bytes) {
        try (var input = javax.imageio.ImageIO.createImageInputStream(new java.io.ByteArrayInputStream(bytes))) {
            var readers = javax.imageio.ImageIO.getImageReaders(input);
            require(readers.hasNext(), "project_seal_invalid");
            var reader = readers.next();
            try {
                reader.setInput(input);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                require(width > 0 && height > 0 && width <= 4096 && height <= 4096, "project_seal_invalid");
                require(reader.read(0) != null, "project_seal_invalid");
            } finally {
                reader.dispose();
            }
        } catch (java.io.IOException failure) {
            throw error("project_seal_invalid", 409);
        }
    }
}
