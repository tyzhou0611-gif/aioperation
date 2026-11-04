package com.aiops.infrastructure.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 告警通知服务（支持邮件、钉钉、企微）
 */
@Service
@ConditionalOnProperty(name = "app.notification.enabled", havingValue = "true", matchIfMissing = true)
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    @Async
    public void sendEmail(String to, String subject, String content) {
        // TODO: 集成 JavaMailSender
        log.info("[邮件通知] 收件人: {} | 主题: {} | 内容: {}", to, subject, content);
    }

    @Async
    public void sendDingTalk(String webhook, String title, String content) {
        // TODO: 使用 RestTemplate 发送 Markdown 到钉钉机器人
        log.info("[钉钉通知] Webhook: {} | 标题: {} | 内容: {}", webhook, title, content);
    }

    @Async
    public void sendWeChat(String webhook, String content) {
        // TODO: 使用 RestTemplate 发送文本消息到企业微信机器人
        log.info("[企微通知] Webhook: {} | 内容: {}", webhook, content);
    }

    /**
     * 根据通知渠道配置发送通知
     */
    @Async
    public void notify(String channelsJson, String configJson, String title, String content) {
        if (channelsJson == null || channelsJson.trim().isEmpty()) {
            log.warn("未配置通知渠道，跳过通知");
            return;
        }
        try {
            String cleaned = channelsJson.replaceAll("[\\[\\]\" ]", "");
            String[] channels = cleaned.split(",");
            for (String ch : channels) {
                String c = ch.trim();
                if ("email".equals(c)) sendEmail("ops@aiops.com", title, content);
                else if ("dingtalk".equals(c)) sendDingTalk("https://oapi.dingtalk.com/robot/send", title, content);
                else if ("wechat".equals(c)) sendWeChat("https://qyapi.weixin.qq.com/cgi-bin/webhook/send", content);
                else log.warn("未知通知渠道: {}", ch);
            }
        } catch (Exception e) {
            log.error("通知发送失败", e);
        }
    }
}