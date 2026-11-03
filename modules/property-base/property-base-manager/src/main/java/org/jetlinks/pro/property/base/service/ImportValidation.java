package org.jetlinks.pro.property.base.service;

import org.hswebframework.web.exception.BusinessException;
import org.hswebframework.web.i18n.LocaleUtils;

import reactor.core.publisher.Mono;

import java.util.function.Supplier;

/** 保留真实工作表行号；只包装可公开的业务错误，不泄漏数据库及内部异常。 */
final class ImportValidation {
    private ImportValidation() {}

    static <T> Mono<T> at(int row, Supplier<Mono<T>> action) {
        return Mono.defer(action)
                .onErrorResume(
                        BusinessException.class,
                        error ->
                                LocaleUtils.resolveThrowable(
                                                error,
                                                (cause, message) ->
                                                        new BusinessException.NoStackTrace(
                                                                "error.property_excel_row",
                                                                "error.property_excel_row",
                                                                error.getStatus(),
                                                                row,
                                                                message))
                                        .flatMap(Mono::error))
                .onErrorResume(
                        org.hswebframework.web.authorization.exception.AccessDenyException.class,
                        error ->
                                Mono.error(
                                        new BusinessException.NoStackTrace(
                                                "error.property_excel_permission",
                                                "error.property_excel_permission",
                                                403,
                                                row)));
    }
}
