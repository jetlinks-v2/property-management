package org.jetlinks.pro.property.decoration.web;

import lombok.RequiredArgsConstructor;
import org.hswebframework.web.authorization.annotation.Authorize;
import org.jetlinks.pro.property.decoration.service.DecorationMobileIdentityService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** 只返回登录本人可进入的端别，应用 Scope 仍由平台和业务边界核验。 */
@RestController @Authorize @RequiredArgsConstructor
public class DecorationMobileIdentityController {
    private final DecorationMobileIdentityService identities;
    @GetMapping("/decoration/mobile/identity")
    public Mono<DecorationMobileIdentityService.Identity> current() { return identities.current(); }
}
