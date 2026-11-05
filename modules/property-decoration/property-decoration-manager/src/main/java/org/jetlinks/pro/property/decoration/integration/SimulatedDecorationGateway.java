package org.jetlinks.pro.property.decoration.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.jetlinks.pro.property.decoration.entity.DecorationSimulationReceiptEntity;
import org.jetlinks.pro.property.decoration.model.DecorationFinanceGateway;
import org.jetlinks.pro.property.decoration.model.DecorationNotificationGateway;
import org.jetlinks.pro.property.decoration.service.DecorationApplicationService;
import org.jetlinks.pro.property.decoration.service.DecorationRules;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.function.Supplier;

/** 用户授权的本机测试提供方。默认关闭，provider显式标注模拟；不调用财务/支付/推送系统。 */
@Component
@ConditionalOnProperty(name = "decoration.integrations.simulated.enabled", havingValue = "true")
@RequiredArgsConstructor
public class SimulatedDecorationGateway implements DecorationFinanceGateway, DecorationNotificationGateway {
    public static final String PROVIDER = "SIMULATED_DECORATION";
    private final ReactiveRepository<DecorationSimulationReceiptEntity, String> receipts;
    private final SimulationReceiptWriter writer;
    private final ObjectMapper mapper;

    @Override public Mono<Result> payment(Request request) { return money(request, Direction.PAYMENT); }
    @Override public Mono<Result> refund(Request request) { return money(request, Direction.REFUND); }
    private Mono<Result> money(Request request, Direction direction) {
        return receipt(direction.name(), request.requestKey(), request, Result.class, () -> new Result(
            request.decorationId(), request.applicationId(), request.tenantId(), request.projectId(), request.spaceId(),
            request.ownerUserId(), request.chargeItemId(), request.amount(), PROVIDER,
            request.offline() ? request.reference() : "SIM-" + direction.name() + "-" + request.requestKey(),
            direction, request.offline(), Status.SUCCEEDED, System.currentTimeMillis()));
    }
    @Override public Mono<Receipt> send(Notice notice) {
        return receipt("NOTICE", notice.requestId(), notice, Receipt.class,
            () -> new Receipt(PROVIDER, "SIM-NOTICE-" + notice.requestId(), true));
    }
    private <T> Mono<T> receipt(String operation, String key, Object request, Class<T> type, Supplier<T> result) {
        return Mono.defer(() -> {
            String id = DecorationApplicationService.identity(PROVIDER + ":" + operation + ":" + key);
            String fingerprint = digest(encode(request));
            return receipts.findById(id).switchIfEmpty(Mono.defer(() -> {
                var row = new DecorationSimulationReceiptEntity(); row.setId(id); row.setOperation(operation);
                row.setFingerprint(fingerprint); row.setResult(encode(result.get())); row.setCreateTime(System.currentTimeMillis());
                return writer.insert(row).thenReturn(row)
                    // 唯一键竞争在独立事务结束后核对赢家，不能在已失败事务中继续查询。
                    .onErrorResume(error -> receipts.findById(id).switchIfEmpty(Mono.error(error)));
            })).map(row -> {
                if (!row.getFingerprint().equals(fingerprint)) throw DecorationRules.error("request_conflict", 409);
                try { return mapper.readValue(row.getResult(), type); }
                catch (java.io.IOException error) { throw new IllegalStateException(error); }
            });
        });
    }
    private String encode(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (com.fasterxml.jackson.core.JsonProcessingException error) { throw new IllegalArgumentException(error); }
    }
    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
}
