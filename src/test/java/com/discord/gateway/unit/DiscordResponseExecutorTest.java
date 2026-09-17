package com.discord.gateway.unit;

import com.discord.gateway.executor.BotMessageRegistry;
import com.discord.gateway.executor.DiscordResponseExecutor;
import com.discord.gateway.executor.InteractionHookRegistry;
import com.discord.gateway.model.OutboundResponsePayload;
import com.discord.gateway.relay.AttachmentDownloader;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.requests.restaction.WebhookMessageCreateAction;
import net.dv8tion.jda.api.requests.restaction.WebhookMessageEditAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
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
        when(hookRegistry.getHook(anyString())).thenReturn(Optional.of(hook));
    }

    @Test
    @SuppressWarnings("unchecked")
    void replyCallsSendMessage() {
        var msgAction = mock(WebhookMessageCreateAction.class);
        when(hook.sendMessage(anyString())).thenReturn(msgAction);

        var payload = new OutboundResponsePayload(
                "REPLY", "token123", null, null, "Hello!", null, null, null, null);
        var result = executor.execute(payload);

        verify(hook).sendMessage("Hello!");
        assertThat(result.success()).isTrue();
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
}
