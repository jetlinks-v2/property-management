package org.jetlinks.pro.property.facility.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.hswebframework.web.authorization.annotation.Authorize;
import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.pro.property.facility.service.NotificationStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

/** 受信短信回执适配入口；网关必须原样传递正文，签名内容为时间戳+换行+正文。 */
@RestController
@RequestMapping("/property/facility-energy/callbacks/sms")
public class FacilityReceiptController {

  private final NotificationStore store;
  private final ObjectMapper mapper;
  private final String secret;

  public FacilityReceiptController(
    NotificationStore store,
    ObjectMapper mapper,
    @Value("${facility.sms.receipt-secret:}") String secret
  ) {
    this.store = store;
    this.mapper = mapper;
    this.secret = secret;
  }

  public record Receipt(
    String notificationId,
    String notifierId,
    String sendId,
    String receiver,
    boolean delivered,
    String reason
  ) {}

  @PostMapping
  @Authorize(ignore = true)
  public Mono<Void> receipt(
    @RequestHeader("X-Receipt-Timestamp") long timestamp,
    @RequestHeader("X-Receipt-Signature") String signature,
    @RequestBody String body
  ) {
    return Mono.defer(() -> {
      if (
        secret.length() < 32 ||
        Math.abs(System.currentTimeMillis() - timestamp) > 300000 ||
        body.length() > 16384
      ) throw new BusinessException("error.facility.invalid_receipt", 403);
      final Receipt receipt;
      try {
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(
          new SecretKeySpec(
            secret.getBytes(StandardCharsets.UTF_8),
            "HmacSHA256"
          )
        );
        var actual = mac.doFinal(
          (timestamp + "\n" + body).getBytes(StandardCharsets.UTF_8)
        );
        if (
          !MessageDigest.isEqual(actual, HexFormat.of().parseHex(signature))
        ) throw new IllegalArgumentException();
        receipt = mapper.readValue(body, Receipt.class);
      } catch (Exception e) {
        throw new BusinessException("error.facility.invalid_receipt", 403);
      }
      return store
        .createUpdate()
        .set("state", receipt.delivered() ? "delivered" : "failed")
        .set(
          "deliveredTime",
          receipt.delivered() ? System.currentTimeMillis() : null
        )
        .set("failureReason", receipt.reason())
        .where("id", receipt.notificationId())
        .and("notifierId", receipt.notifierId())
        .and("providerMessageId", receipt.sendId())
        .and("telephone", receipt.receiver())
        .and("channel", "sms")
        .in("state", java.util.List.of("submitted", "uncertain"))
        .execute()
        .then();
    });
  }
}
