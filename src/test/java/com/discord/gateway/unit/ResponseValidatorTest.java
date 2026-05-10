package com.discord.gateway.unit;

import com.discord.gateway.dispatcher.ResponseValidator;
import com.discord.gateway.model.OutboundResponsePayload;
import com.discord.gateway.model.PayloadValidationException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResponseValidatorTest {

    private final ResponseValidator validator = new ResponseValidator();

    @Test
    void validReplyPayloadPasses() {
        var payload = new OutboundResponsePayload(
                "REPLY", "token123", null, null, "Hello!", null, "corr-id");
        assertThatNoException().isThrownBy(() -> validator.validate(payload, "{}"));
    }

    @Test
    void missingResponseTypeIsRejected() {
        var payload = new OutboundResponsePayload(
                null, "token123", null, null, "Hello!", null, null);
        assertThatThrownBy(() -> validator.validate(payload, "{}"))
                .isInstanceOf(PayloadValidationException.class)
                .satisfies(ex -> assertThat(((PayloadValidationException) ex).getMissingFields())
                        .contains("responseType"));
    }

    @Test
    void unknownResponseTypeIsRejected() {
        var payload = new OutboundResponsePayload(
                "UNKNOWN_TYPE", "token123", null, null, "Hello!", null, null);
        assertThatThrownBy(() -> validator.validate(payload, "{}"))
                .isInstanceOf(PayloadValidationException.class)
                .satisfies(ex -> assertThat(((PayloadValidationException) ex).getReason())
                        .contains("responseType"));
    }

    @Test
    void updateMessageWithoutInteractionTokenIsRejected() {
        var payload = new OutboundResponsePayload(
                "UPDATE_MESSAGE", null, "msg123", null, "Content", null, null);
        assertThatThrownBy(() -> validator.validate(payload, "{}"))
                .isInstanceOf(PayloadValidationException.class)
                .satisfies(ex -> assertThat(((PayloadValidationException) ex).getMissingFields())
                        .contains("interactionToken"));
    }

    @Test
    void updateMessageWithoutMessageIdIsRejected() {
        var payload = new OutboundResponsePayload(
                "UPDATE_MESSAGE", "token123", null, null, "Content", null, null);
        assertThatThrownBy(() -> validator.validate(payload, "{}"))
                .isInstanceOf(PayloadValidationException.class)
                .satisfies(ex -> assertThat(((PayloadValidationException) ex).getMissingFields())
                        .contains("messageId"));
    }

    @Test
    void missingContentAndEmbedsIsRejected() {
        var payload = new OutboundResponsePayload(
                "REPLY", "token123", null, null, null, null, null);
        assertThatThrownBy(() -> validator.validate(payload, "{}"))
                .isInstanceOf(PayloadValidationException.class)
                .satisfies(ex -> assertThat(((PayloadValidationException) ex).getMissingFields())
                        .containsAnyOf("content", "embeds"));
    }

    @Test
    void validEphemeralReplyPasses() {
        var payload = new OutboundResponsePayload(
                "EPHEMERAL_REPLY", "token123", null, null, "Only you can see this", null, null);
        assertThatNoException().isThrownBy(() -> validator.validate(payload, "{}"));
    }

    @Test
    void deferredReplyWithoutContentIsRejected() {
        // DEFERRED_REPLY sends a followup to a previously-deferred interaction — content required
        var payload = new OutboundResponsePayload(
                "DEFERRED_REPLY", "token123", null, null, null, null, null);
        assertThatThrownBy(() -> validator.validate(payload, "{}"))
                .isInstanceOf(PayloadValidationException.class)
                .satisfies(ex -> assertThat(((PayloadValidationException) ex).getMissingFields())
                        .containsAnyOf("content", "embeds"));
    }

    @Test
    void deferredUpdateWithoutContentPasses() {
        // DEFERRED_UPDATE edits the original message; empty content = keep as-is (valid Discord behavior)
        var payload = new OutboundResponsePayload(
                "DEFERRED_UPDATE", "token123", "msg123", null, null, null, null);
        assertThatNoException().isThrownBy(() -> validator.validate(payload, "{}"));
    }

    @Test
    void replyMissingInteractionTokenIsRejected() {
        var payload = new OutboundResponsePayload(
                "REPLY", null, null, null, "Hello!", null, null);
        assertThatThrownBy(() -> validator.validate(payload, "{}"))
                .isInstanceOf(PayloadValidationException.class)
                .satisfies(ex -> assertThat(((PayloadValidationException) ex).getMissingFields())
                        .contains("interactionToken"));
    }
}
