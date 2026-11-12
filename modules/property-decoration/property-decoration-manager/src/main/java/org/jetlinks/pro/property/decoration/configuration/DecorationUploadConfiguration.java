package org.jetlinks.pro.property.decoration.configuration;

import org.jetlinks.pro.io.file.FileProperties;
import org.jetlinks.pro.io.filter.FileUploadWebFilter;
import org.jetlinks.pro.property.decoration.service.DecorationFileStorage;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** 保留公共上传校验，同时恢复重建请求的 HTTP 请求头大小写无关语义。 */
@Configuration
public class DecorationUploadConfiguration {
    @Bean
    public WebFilter decorationFileUploadWebFilter(FileProperties properties) {
        return new HeaderPreservingUploadFilter(new FileUploadWebFilter(properties));
    }

    static final class HeaderPreservingUploadFilter implements WebFilter {
        private final FileUploadWebFilter delegate;

        HeaderPreservingUploadFilter(FileUploadWebFilter delegate) {
            this.delegate = delegate;
        }

        @Override
        public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
            return delegate.filter(exchange, rebuilt -> {
                // io-component 2.20 的 multipart 重建使用大小写敏感的 MultiValueMap。
                // 只修复头容器，不推断租户、不改变认证或文件内容及校验规则。
                HttpHeaders headers = new HttpHeaders();
                headers.putAll(rebuilt.getRequest().getHeaders());
                var request = new ServerHttpRequestDecorator(rebuilt.getRequest()) {
                    @Override
                    public HttpHeaders getHeaders() {
                        return headers;
                    }
                };
                return chain.filter(rebuilt.mutate().request(request).build());
            }).contextWrite(context -> DecorationFileStorage.withRequestHeaders(context, exchange.getRequest().getHeaders()));
        }
    }
}
