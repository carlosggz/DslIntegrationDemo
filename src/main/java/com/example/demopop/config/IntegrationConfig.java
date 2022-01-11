package com.example.demopop.config;

import com.example.demopop.components.ChannelRoutingComponent;
import com.example.demopop.components.DtoTransformerComponent;
import com.example.demopop.components.MailTransformerComponent;
import com.example.demopop.components.RemoveFileInterceptorComponent;
import com.example.demopop.components.SendMailsComponent;
import com.example.demopop.config.mail.MailSettings;
import com.example.demopop.config.queue.QueueSettings;
import com.example.demopop.models.MailInfoDto;
import com.example.demopop.services.InvalidMessageService;
import com.example.demopop.services.LoggingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.apache.commons.net.ftp.FTPFile;
import org.springframework.amqp.core.AmqpTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.expression.common.LiteralExpression;
import org.springframework.http.HttpMethod;
import org.springframework.integration.amqp.outbound.AmqpOutboundEndpoint;
import org.springframework.integration.core.MessageSource;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.integration.dsl.IntegrationFlows;
import org.springframework.integration.dsl.Pollers;
import org.springframework.integration.file.remote.session.SessionFactory;
import org.springframework.integration.ftp.outbound.FtpMessageHandler;
import org.springframework.integration.handler.LoggingHandler;
import org.springframework.integration.handler.MessageHandlerChain;
import org.springframework.integration.http.outbound.HttpRequestExecutingMessageHandler;
import org.springframework.integration.mail.MailReceiver;
import org.springframework.integration.mail.MailReceivingMessageSource;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHandler;

import java.io.File;
import java.util.List;

@Configuration
@RequiredArgsConstructor
@Slf4j
public class IntegrationConfig {

    private final MailReceiver mailReceiver;
    private final QueueSettings queueSettings;
    private final MailTransformerComponent mailTransformer;
    private final ChannelRoutingComponent channelRouting;
    private final InvalidMessageService invalidMessageService;
    private final DtoTransformerComponent dtoTransformer;
    private final RemoveFileInterceptorComponent removeFileInterceptor;
    private final LoggingService loggingService;
    private final MailSettings mailSettings;

    @Value("${app.http.url}")
    String url;
    @Value("${app.fakerEnabled}")
    boolean fakerEnabled;
    @Value("${app.cron}")
    String cron;

    MessageSource<?> mailReceiver() {
        return new MailReceivingMessageSource(mailReceiver);
    }

    MessageSource<?> fakeMailer() {
        SendMailsComponent sendMailsComponent = new SendMailsComponent(mailSettings);
        return sendMailsComponent::getMail;
    }

    @Bean(name = "outboundFtpHandler")
    public MessageHandler outboundFtpHandler(SessionFactory<FTPFile> sessionFactory) {
        val handler = new FtpMessageHandler(sessionFactory);
        handler.setFileNameGenerator(message -> ((File) message.getPayload()).getName());
        handler.setRemoteDirectoryExpression(new LiteralExpression(""));
        handler.setLoggingEnabled(false);
        handler.setUseTemporaryFileName(true);
        handler.setShouldTrack(true);
        return handler;
    }

    @Bean(name = "outboundAmqpHandler")
    public MessageHandler outboundAmqpHandler(AmqpTemplate amqpTemplate) {
        AmqpOutboundEndpoint outbound = new AmqpOutboundEndpoint(amqpTemplate);
        outbound.setExchangeName(queueSettings.getExchange());
        outbound.setRoutingKey(queueSettings.getRoutingKey());
        return outbound;
    }

    MessageHandler httpHandler() {
        HttpRequestExecutingMessageHandler handler = new HttpRequestExecutingMessageHandler(url);
        handler.setHttpMethod(HttpMethod.POST);
        handler.setLoggingEnabled(false);
        handler.setExpectedResponseType(String.class);
        handler.setExpectReply(true);
        return handler;
    }

    MessageHandler responseHttpHandler() {
        return message -> log.info("Response from http: {}", message.getPayload());
    }

    @Bean(name = "outboundHttpHandler")
    public MessageHandler outboundHttpChain() {
        var chain = new MessageHandlerChain();
        chain.setHandlers(List.of(httpHandler(), responseHttpHandler()));
        return chain;
    }

    @Bean
    public IntegrationFlow dslIntegrationFlow(
            AmqpTemplate amqpTemplate,
            SessionFactory<FTPFile> sessionFactory) {
        return IntegrationFlows
                .from(
                        fakerEnabled ? fakeMailer() : mailReceiver(),
                        c -> c.poller(Pollers.cron(cron)))
                .channel(ConstantsUtils.INBOUND_MAIL_CHANNEL)
                .transform(mailTransformer::mailToDto)
                .channel(ConstantsUtils.ROUTING_CHANNEL)
                .publishSubscribeChannel(c -> c
                        .subscribe(s -> s.handle(m -> loggingService.logDto((MailInfoDto) m.getPayload())))
                        .subscribe(s -> s
                                .<MailInfoDto, String>route(channelRouting::getRoute, mapping -> mapping
                                        .subFlowMapping(
                                                ConstantsUtils.OUTBOUND_HTTP_CHANNEL,
                                                sf -> sf
                                                        .channel(ConstantsUtils.OUTBOUND_HTTP_CHANNEL)
                                                        .handle(outboundHttpChain())
                                        )
                                        .subFlowMapping(
                                                ConstantsUtils.CANCELLATION_CHANNEL,
                                                sf -> sf
                                                        .transform(dtoTransformer::dtoToFile)
                                                        .channel(ConstantsUtils.OUTBOUND_FTP_CHANNEL)
                                                        .intercept(removeFileInterceptor)
                                                        .handle(outboundFtpHandler(sessionFactory))
                                        )
                                        .subFlowMapping(
                                                ConstantsUtils.CONFIRMATION_CHANNEL,
                                                sf -> sf
                                                        .transform(dtoTransformer::dtoToJson)
                                                        .channel(ConstantsUtils.OUTBOUND_QUEUE_CHANNEL)
                                                        .handle(outboundAmqpHandler(amqpTemplate))
                                        )
                                        .subFlowMapping(
                                                ConstantsUtils.INVALID_CHANNEL,
                                                sf -> sf
                                                        .channel(ConstantsUtils.INVALID_CHANNEL)
                                                        .handle(message -> invalidMessageService.process((MailInfoDto) message.getPayload()))
                                        )
                                )
                        )
                )
                .get();
    }
}