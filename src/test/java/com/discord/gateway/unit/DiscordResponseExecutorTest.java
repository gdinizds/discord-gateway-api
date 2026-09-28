package com.discord.gateway.unit;

import com.discord.gateway.executor.BotMessageRegistry;
import com.discord.gateway.executor.DiscordResponseExecutor;
import com.discord.gateway.executor.InteractionHookRegistry;
import com.discord.gateway.model.OutboundResponsePayload;
import com.discord.gateway.relay.AttachmentDownloader;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction;
import net.dv8tion.jda.api.requests.restaction.WebhookMessageCreateAction;
import net.dv8tion.jda.api.requests.restaction.WebhookMessageEditAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DiscordResponseExecutorTest {

    @Mock
    private InteractionHookRegistry hookRegistry;

    @Mock
    private InteractionHook hook;

    @Mock
    private AttachmentDownloader attachmentDownloader;

    @Mock
    private JDA jda;

    @Mock
    private BotMessageRegistry botMessageRegistry;

    private DiscordResponseExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new DiscordResponseExecutor(hookRegistry, botMessageRegistry, attachmentDownloader, jda);
        lenient().when(hookRegistry.getHook(anyString())).thenReturn(Optional.of(hook));
    }

    @Test
    @SuppressWarnings("unchecked")
    void replyCallsSendMessage() {
        var msgAction = mock(WebhookMessageCreateAction.class);
        when(hook.sendMessage(anyString())).thenReturn(msgAction);
        var sent = message("sent-1", "chan-1");
        when(msgAction.submit()).thenReturn(CompletableFuture.completedFuture(sent));

        var payload = new OutboundResponsePayload(
                "REPLY", "token123", null, null, "Hello!", null, null, null, null);
        var result = executor.execute(payload);

        verify(hook).sendMessage("Hello!");
        assertThat(result.success()).isTrue();
        assertThat(result.discordMessageId()).isEqualTo("sent-1");
        assertThat(result.discordChannelId()).isEqualTo("chan-1");
    }

    @Test
    @SuppressWarnings("unchecked")
    void ephemeralReplyCallsSendMessageWithEphemeralFlag() {
        var msgAction = mock(WebhookMessageCreateAction.class);
        when(hook.sendMessage(anyString())).thenReturn(msgAction);
        when(msgAction.setEphemeral(anyBoolean())).thenReturn(msgAction);

        var payload = new OutboundResponsePayload(
                "EPHEMERAL_REPLY", "token123", null, null, "Only you!", null, null, null, null);
        executor.execute(payload);

        verify(hook).sendMessage("Only you!");
        verify(msgAction).setEphemeral(true);
    }

    @Test
    @SuppressWarnings("unchecked")
    void updateMessageCallsEditOriginal() {
        var editAction = mock(WebhookMessageEditAction.class);
        when(hook.editOriginal(anyString())).thenReturn(editAction);

        var payload = new OutboundResponsePayload(
                "UPDATE_MESSAGE", "token123", "msg123", null, "Edited!", null, null, null, null);
        executor.execute(payload);

        verify(hook).editOriginal("Edited!");
    }

    @Test
    @SuppressWarnings("unchecked")
    void deferredReplyEditsOriginal() {
        // DEFERRED_REPLY edits the loading message created by deferReply()
        var editAction = mock(WebhookMessageEditAction.class);
        when(hook.editOriginal(anyString())).thenReturn(editAction);

        var payload = new OutboundResponsePayload(
                "DEFERRED_REPLY", "token123", null, null, "Processing done!", null, null, null, null);
        executor.execute(payload);

        verify(hook).editOriginal("Processing done!");
    }

    @Test
    @SuppressWarnings("unchecked")
    void deferredUpdateCallsEditOriginal() {
        var editAction = mock(WebhookMessageEditAction.class);
        when(hook.editOriginal(anyString())).thenReturn(editAction);

        var payload = new OutboundResponsePayload(
                "DEFERRED_UPDATE", "token123", "msg123", null, "Updated!", null, null, null, null);
        executor.execute(payload);

        verify(hook).editOriginal("Updated!");
    }

    @Test
    void hookNotFoundReturnsFailureResult() {
        when(hookRegistry.getHook(anyString())).thenReturn(Optional.empty());

        var payload = new OutboundResponsePayload(
                "REPLY", "missing-token", null, null, "Hello!", null, null, null, null);
        var result = executor.execute(payload);

        assertThat(result.success()).isFalse();
        assertThat(result.discordError()).contains("hook");
    }

    @Test
    @SuppressWarnings("unchecked")
    void channelReplyWorksInThreads() {
        var thread = mock(ThreadChannel.class);
        var createAction = mock(MessageCreateAction.class, RETURNS_SELF);
        when(jda.getChannelById(GuildMessageChannel.class, "thread-1")).thenReturn(thread);
        when(thread.sendMessage(anyString())).thenReturn(createAction);
        var sent = message("bot-1", "thread-1");
        doReturn(CompletableFuture.completedFuture(sent)).when(createAction).submit();

        var payload = new OutboundResponsePayload(
                "REPLY", null, "origin-1", "thread-1", "Oi", null, null, null, null);
        var result = executor.execute(payload);

        assertThat(result.success()).isTrue();
        verify(thread).sendMessage("Oi");
        verify(createAction).setMessageReference("origin-1");
        verify(botMessageRegistry).register("origin-1", "bot-1");
    }

    @Test
    @SuppressWarnings("unchecked")
    void discordRejectionReturnsFailureAndSendsFallback() {
        var msgAction = mock(WebhookMessageCreateAction.class);
        var fallback = mock(WebhookMessageCreateAction.class);
        when(hook.sendMessage("Hello!")).thenReturn(msgAction);
        when(hook.sendMessage("Ocorreu uma falha ao enviar a resposta.")).thenReturn(fallback);
        when(fallback.setEphemeral(true)).thenReturn(fallback);
        when(msgAction.submit()).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("Unknown Webhook")));

        var result = executor.execute(new OutboundResponsePayload(
                "REPLY", "token123", null, null, "Hello!", null, null, null, null));

        assertThat(result.success()).isFalse();
        assertThat(result.discordError()).contains("Unknown Webhook");
        verify(fallback).queue(isNull(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void slowDiscordReturnsTimeoutFailure() {
        var slowExecutor = new DiscordResponseExecutor(hookRegistry, botMessageRegistry, attachmentDownloader, jda,
                Duration.ofMillis(50));
        var msgAction = mock(WebhookMessageCreateAction.class);
        when(hook.sendMessage(anyString())).thenReturn(msgAction);
        when(msgAction.submit()).thenReturn(new CompletableFuture<>());

        var result = slowExecutor.execute(new OutboundResponsePayload(
                "REPLY", "token123", null, null, "Hello!", null, null, null, null));

        assertThat(result.success()).isFalse();
        assertThat(result.discordError()).contains("timeout");
    }

    private static Message message(String id, String channelId) {
        var message = mock(Message.class);
        lenient().when(message.getId()).thenReturn(id);
        lenient().when(message.getChannelId()).thenReturn(channelId);
        return message;
    }
}
