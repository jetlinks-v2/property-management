package org.jetlinks.pro.property.quality.web;

import org.hswebframework.web.crud.web.CommonErrorControllerAdvice;
import org.hswebframework.web.crud.web.ResponseMessage;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.DisconnectedClientHelper;
import reactor.core.publisher.Mono;
import reactor.netty.channel.AbortedException;

/** 客户端取消已开始的响应时，不再向已关闭连接写入第二个错误响应。 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ClientDisconnectAdvice {

    private static final DisconnectedClientHelper DISCONNECTED_CLIENTS =
        new DisconnectedClientHelper(ClientDisconnectAdvice.class.getName());

    private final CommonErrorControllerAdvice errors;

    public ClientDisconnectAdvice(CommonErrorControllerAdvice errors) {
        this.errors = errors;
    }

    @ExceptionHandler(AbortedException.class)
    public Mono<ResponseEntity<ResponseMessage<Object>>> handleDisconnect(AbortedException error,
                                                                          ServerWebExchange exchange) {
        // 只结束已提交响应的客户端断连；上游调用失败及未提交响应仍走框架错误协议。
        if (exchange.getResponse().isCommitted()
            && DISCONNECTED_CLIENTS.checkAndLogClientDisconnectedException(error)) {
            return Mono.empty();
        }
        return errors.handleException(error)
            .map(message -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(message));
    }
}
