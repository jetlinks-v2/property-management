package org.jetlinks.pro.property.decoration.integration;

import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.i18n.LocaleUtils;
import org.jetlinks.core.metadata.ConfigMetadata;
import org.jetlinks.core.metadata.DefaultConfigMetadata;
import org.jetlinks.pro.notify.enums.SubscriberTypeEnum;
import org.jetlinks.pro.notify.subscription.SubscribeType;
import org.jetlinks.pro.notify.subscription.Subscriber;
import org.jetlinks.pro.notify.subscription.SubscriberProvider;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import java.util.Map;

/** Cloud 推送式业务通知来源；收件人由装修聚合业主快照确定，不接受任意订阅配置。 */
@Component
public class DecorationNotifySubscriberProvider implements SubscriberProvider {
    public static final String ID="property-decoration";
    @Override public String getId(){return ID;}
    @Override public String getName(){return LocaleUtils.resolveMessage("message.decoration.notice.title","装修办理提醒");}
    @Override public Integer getOrder(){return 310;}
    @Override public SubscribeType getType(){return SubscriberTypeEnum.businessEvent;}
    @Override public boolean isPushModeEnabled(){return true;}
    @Override public ConfigMetadata getConfigMetadata(){return new DefaultConfigMetadata();}
    @Override public Mono<Subscriber> createSubscriber(String id,Authentication authentication,Map<String,Object> config){
        return Mono.error(new UnsupportedOperationException("Decoration notifications use push mode"));
    }
}
