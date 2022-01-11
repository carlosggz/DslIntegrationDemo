package com.example.demopop.components;

import com.example.demopop.config.mail.MailSettings;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.apache.commons.lang3.RandomUtils;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;

import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.internet.MimeMessage;
import java.util.List;

@Slf4j
public class SendMailsComponent {
    final static List<String> SUBJECTS = List.of("Info Required", "Confirmation", "Cancellation", "Other");

    private final MailSettings mailSettings;

    public SendMailsComponent(MailSettings mailSettings) {
        this.mailSettings = mailSettings;
        log.info("Fake mailer started!!!");
    }

    public Message<Object> getMail() {
        try {
            val message = getMessage();
            log.info("Generated mail with subject: {}", message.getSubject());
            return MessageBuilder.withPayload((Object)message).build();
        } catch (Exception ex) {
            log.error("Error sending mail: {}", ex.getMessage());
            return null;
        }
    }

    private MimeMessage getMessage() throws MessagingException {
        val mimeMessage = new MimeMessage((Session)null);
        mimeMessage.setSubject(getSubject());
        mimeMessage.setFrom(mailSettings.getEmail());
        mimeMessage.setContent("Content of the mail", "Text/Plain");
        return mimeMessage;
    }

    private String getSubject() {
        val action = SUBJECTS.get(RandomUtils.nextInt(0, SUBJECTS.size()));
        val reservationNumber = RandomUtils.nextInt(1, 100);
        return String.format("%s - [%s]", action, reservationNumber);
    }
}
