package dev.tintwym.home_mart_backend.service;

import dev.tintwym.home_mart_backend.config.MailAppProperties;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * Sends email via Resend API, then SMTP, then logs. Errors are swallowed.
 */
@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    private final MailAppProperties mailAppProperties;
    private final ObjectProvider<JavaMailSender> mailSender;
    private final RestClient restClient;
    private final String smtpHost;

    public EmailService(
            MailAppProperties mailAppProperties,
            ObjectProvider<JavaMailSender> mailSender,
            @Value("${spring.mail.host:}") String smtpHost) {
        this.mailAppProperties = mailAppProperties;
        this.mailSender = mailSender;
        this.smtpHost = smtpHost;
        this.restClient = RestClient.create();
    }

    public void send(String to, String subject, String textBody) {
        send(to, subject, textBody, null);
    }

    public void send(String to, String subject, String textBody, String htmlBody) {
        try {
            if (tryResend(to, subject, textBody, htmlBody)) {
                return;
            }
            if (trySmtp(to, subject, textBody)) {
                return;
            }
            log.info("Email (log fallback) to={} subject={} body={}", to, subject, textBody);
        } catch (Exception e) {
            log.warn("Email send failed (swallowed): {}", e.getMessage());
        }
    }

    private boolean tryResend(String to, String subject, String textBody, String htmlBody) {
        String apiKey = mailAppProperties.getResendApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            return false;
        }
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            String from = mailAppProperties.getFromName() + " <" + mailAppProperties.getFromAddress() + ">";
            body.put("from", from);
            body.put("to", new String[] {to});
            body.put("subject", subject);
            if (htmlBody != null && !htmlBody.isBlank()) {
                body.put("html", htmlBody);
            }
            if (textBody != null && !textBody.isBlank()) {
                body.put("text", textBody);
            }

            restClient.post()
                    .uri("https://api.resend.com/emails")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + apiKey)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (Exception e) {
            log.warn("Resend API failed: {}", e.getMessage());
            return false;
        }
    }

    private boolean trySmtp(String to, String subject, String textBody) {
        if (smtpHost == null || smtpHost.isBlank()) {
            return false;
        }
        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender == null) {
            return false;
        }
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(mailAppProperties.getFromAddress());
            message.setTo(to);
            message.setSubject(subject);
            message.setText(textBody == null ? "" : textBody);
            sender.send(message);
            return true;
        } catch (Exception e) {
            log.warn("SMTP send failed: {}", e.getMessage());
            return false;
        }
    }
}
