package org.jetlinks.pro.property.decoration.integration;

import org.jetlinks.pro.command.CommandServices;
import org.jetlinks.pro.notify.command.NotifyCommandSupportTypes;
import org.jetlinks.pro.notify.command.SendUserNotificationCommand;
import org.jetlinks.pro.property.decoration.entity.DecorationNotificationEntity;
import org.jetlinks.sdk.server.SdkServices;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import java.util.Map;

/** 复用公共站内信命令；收件人和跳转目标只取装修已提交快照，不接受客户端覆盖。 */
@Component
public class DecorationInsideMailSender {
    public Mono<Void> send(DecorationNotificationEntity notice) {
        var command=command(notice);
        // 缺少命令提供方不是发送成功；保留发件项供恢复后重试。
        return CommandServices.getReactive(SdkServices.notifyService,NotifyCommandSupportTypes.notification)
            .filterWhen(support->support.commandIsSupported(command).defaultIfEmpty(false))
            .switchIfEmpty(Mono.error(new IllegalStateException("Decoration inside-mail provider unavailable")))
            .flatMap(support->support.execute(command));
    }
    public static SendUserNotificationCommand command(DecorationNotificationEntity notice) {
        return new SendUserNotificationCommand().withUserId(notice.getUserId()).withChannelProvider("inside-mail")
            .withTopicProvider("property-decoration").withTopicName(notice.getTitle()).withSubscribeId("property-decoration")
            .withCode(notice.getEvent()).withDataId(notice.getId()).withNotifyTime(notice.getCreateTime())
            .withMessage(notice.getMessage()).withDetail(Map.of("decorationId",notice.getDecorationId(),
                "applicationId",notice.getApplicationId(),"step",notice.getStep(),"url",notice.getDetailUrl()));
    }
}
