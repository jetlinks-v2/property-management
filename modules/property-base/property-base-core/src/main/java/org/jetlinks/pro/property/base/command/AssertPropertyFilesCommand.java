package org.jetlinks.pro.property.base.command;

import org.jetlinks.core.command.AbstractCommand;
import reactor.core.publisher.Mono;
import java.util.List;

/**
 * 在 Cloud 文件所属服务校验附件是否存在且调用人有权读取。
 * 保留真实认证、租户及资产上下文，不接受调用方传入所有者或租户。
 * 全部通过返回 true；任一附件无权限、缺失或服务不可用均失败，不降级放行。
 */
public class AssertPropertyFilesCommand extends AbstractCommand<Mono<Boolean>, AssertPropertyFilesCommand> {
    public static final String SERVICE_ID = "propertyFileService:attachments";

    @SuppressWarnings("unchecked")
    public List<String> getFileIds() {
        List<String> ids = getOrNull("fileIds", List.class);
        return ids == null ? List.of() : ids;
    }

    public void setFileIds(List<String> ids) {
        with("fileIds", ids);
    }
}
