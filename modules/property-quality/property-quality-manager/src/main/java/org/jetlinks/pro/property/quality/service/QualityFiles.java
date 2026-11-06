package org.jetlinks.pro.property.quality.service;

import org.springframework.stereotype.Component;
import org.jetlinks.pro.command.CommandSupportManagerProviders;
import org.jetlinks.pro.property.base.command.AssertPropertyFilesCommand;
import org.hswebframework.web.exception.BusinessException;
import reactor.core.publisher.Mono;
import java.util.Collection;

/** 由文件所有者校验真实操作人的附件权限；远程失败时禁止关联附件。 */
@Component
public class QualityFiles {
    public Mono<Void> validate(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) return Mono.empty();
        var command = new AssertPropertyFilesCommand();
        command.setFileIds(ids.stream().distinct().toList());
        return CommandSupportManagerProviders.getCommandSupport(AssertPropertyFilesCommand.SERVICE_ID)
            .switchIfEmpty(Mono.error(new BusinessException("error.quality.file_service_unavailable", 503)))
            .flatMap(support -> support.execute(command))
            .filter(Boolean.TRUE::equals)
            .switchIfEmpty(Mono.error(new BusinessException("error.quality.attachment_missing", 400)))
            .then();
    }
}
