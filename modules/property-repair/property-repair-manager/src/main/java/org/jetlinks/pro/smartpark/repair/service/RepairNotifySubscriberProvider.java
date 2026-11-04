package org.jetlinks.pro.smartpark.repair.service;

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

/** Cloud 推送式业务通知来源；收件人由报修授权判定，不接受任意订阅配置。 */
@Component
public class RepairNotifySubscriberProvider implements SubscriberProvider {
    public static final String ID="smart-park-repair";
    @Override public String getId(){return ID;}
    @Override public String getName(){return LocaleUtils.resolveMessage("message.repair.notifications","报事报修通知");}
    @Override public Integer getOrder(){return 310;}
    @Override public SubscribeType getType(){return SubscriberTypeEnum.businessEvent;}
    @Override public boolean isPushModeEnabled(){return true;}
    @Override public ConfigMetadata getConfigMetadata(){return new DefaultConfigMetadata();}
    @Override public Mono<Subscriber> createSubscriber(String id,Authentication authentication,Map<String,Object> config){
        return Mono.error(new UnsupportedOperationException("Repair notifications use push mode"));
    }
}
