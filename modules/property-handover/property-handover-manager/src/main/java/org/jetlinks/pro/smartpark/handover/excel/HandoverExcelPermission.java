package org.jetlinks.pro.smartpark.handover.excel;

import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.authorization.exception.AccessDenyException;

import reactor.core.publisher.Mono;

/** 一个文件入口承接不同资源，按服务器识别的业务类型检查动作，不能把配置导入权限用于问题写入。 */
public final class HandoverExcelPermission {
    private HandoverExcelPermission() {}

    public static String resource(String kind) {
        return switch (kind) {
            case "problem" -> "handover-problem";
            case "record", "item" -> "handover-process";
            case "category", "ledger", "equipment" -> "handover-config";
            default -> throw HandoverWorkbook.invalid(1, "类型", "不支持的导入类型");
        };
    }

    public static Mono<Void> assertImport(String kind) {
        String resource = resource(kind);
        return Authentication.currentReactive()
                .filter(authentication -> authentication.hasPermission(resource, "import"))
                .switchIfEmpty(Mono.error(new AccessDenyException()))
                .then();
    }
}
