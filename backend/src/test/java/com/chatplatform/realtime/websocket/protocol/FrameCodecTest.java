package com.chatplatform.realtime.websocket.protocol;

import com.chatplatform.message.dto.MessageResponse;
import com.chatplatform.realtime.websocket.protocol.Frames.Parsed;
import com.chatplatform.realtime.websocket.protocol.Frames.Rejected;
import com.chatplatform.realtime.websocket.protocol.Frames.SendCommand;
import com.chatplatform.realtime.websocket.protocol.Frames.Violation;
import com.chatplatform.shared.exception.ApiException;
import com.chatplatform.shared.exception.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class FrameCodecTest {

    // Mirrors Spring Boot's ObjectMapper bean: Boot's builder plus its default of writing dates as
    // ISO-8601 strings (spring.jackson.serialization.write-dates-as-timestamps=false). The integration
    // tests use the real bean and compare the ack's createdAt with the database row.
    private final ObjectMapper mapper = Jackson2ObjectMapperBuilder.json()
            .featuresToDisable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();
    private final FrameCodec codec = new FrameCodec(mapper);

    private static final String CONV = "6f1c2a4e-0d1b-4a52-9a77-2f3c1d5e8b90";
    private static final String CMID = "0b6e9a1d-3c2f-4f7a-8e1d-5a9b2c4d6e8f";

    private Parsed parse(String json) {
        return codec.parse(json);
    }

    private static int closeCode(Parsed p) {
        assertThat(p).isInstanceOf(Violation.class);
        return ((Violation) p).status().getCode();
    }

    @Test
    void validSendIsParsed() {
        Parsed p = parse("{\"v\":1,\"type\":\"send\",\"conversationId\":\"" + CONV + "\",\"clientMsgId\":\"" + CMID
                + "\",\"body\":\"hi\",\"extra\":true}");
        assertThat(p).isEqualTo(new SendCommand(UUID.fromString(CONV), UUID.fromString(CMID), "hi"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "not json",
            "[1,2]",
            "{\"type\":\"send\"}",                                   // v missing
            "{\"v\":\"1\",\"type\":\"send\"}",                       // v wrong type
            "{\"v\":1}",                                             // type missing
            "{\"v\":1,\"type\":7}",                                  // type wrong type
            "{\"v\":1,\"type\":\"send\",\"clientMsgId\":\"nope\"}",  // clientMsgId present but not a UUID
            "{\"v\":1,\"type\":\"send\",\"clientMsgId\":42}"         // clientMsgId wrong type
    })
    void unparseableEnvelopeOrClientMsgIdCloses1007(String json) {
        assertThat(closeCode(parse(json))).isEqualTo(1007);
    }

    @Test
    void unsupportedVersionCloses4004() {
        assertThat(closeCode(parse("{\"v\":2,\"type\":\"send\"}"))).isEqualTo(4004);
    }

    @Test
    void unknownTypeCloses4005() {
        assertThat(closeCode(parse("{\"v\":1,\"type\":\"typing\"}"))).isEqualTo(4005);
    }

    @Test
    void invalidFieldWithGoodClientMsgIdIsRejectedWithThatClientMsgId() {
        Parsed badConv = parse("{\"v\":1,\"type\":\"send\",\"conversationId\":\"nope\",\"clientMsgId\":\"" + CMID
                + "\",\"body\":\"hi\"}");
        assertThat(badConv).isInstanceOf(Rejected.class);
        assertThat(((Rejected) badConv).clientMsgId()).isEqualTo(UUID.fromString(CMID));
        assertThat(((Rejected) badConv).code()).isEqualTo("VALIDATION_ERROR");

        Parsed blank = parse("{\"v\":1,\"type\":\"send\",\"conversationId\":\"" + CONV + "\",\"clientMsgId\":\"" + CMID
                + "\",\"body\":\"   \"}");
        assertThat(blank).isInstanceOf(Rejected.class);
        assertThat(((Rejected) blank).clientMsgId()).isEqualTo(UUID.fromString(CMID));
        assertThat(((Rejected) blank).code()).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void missingOrNullClientMsgIdIsRejectedWithNullClientMsgId() {
        for (String json : new String[]{
                "{\"v\":1,\"type\":\"send\",\"conversationId\":\"" + CONV + "\",\"body\":\"hi\"}",
                "{\"v\":1,\"type\":\"send\",\"clientMsgId\":null,\"conversationId\":\"" + CONV + "\",\"body\":\"hi\"}"}) {
            Parsed p = parse(json);
            assertThat(p).as(json).isInstanceOf(Rejected.class);
            assertThat(((Rejected) p).clientMsgId()).isNull();
            assertThat(((Rejected) p).code()).isEqualTo("VALIDATION_ERROR");
        }
    }

    @Test
    void notFoundAndNotMemberGiveIdenticalErrorFramesApartFromClientMsgId() throws Exception {
        // Both cases surface as ApiException(CONVERSATION_NOT_FOUND); the frame must not leak which one.
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        JsonNode missing = mapper.readTree(codec.errorFor(a, new ApiException(ErrorCode.CONVERSATION_NOT_FOUND)));
        JsonNode notMember = mapper.readTree(codec.errorFor(b,
                new ApiException(ErrorCode.CONVERSATION_NOT_FOUND, "detail that must not leak")));
        ((com.fasterxml.jackson.databind.node.ObjectNode) missing).remove("clientMsgId");
        ((com.fasterxml.jackson.databind.node.ObjectNode) notMember).remove("clientMsgId");
        assertThat(missing).isEqualTo(notMember);
        assertThat(missing.get("code").asText()).isEqualTo("CONVERSATION_NOT_FOUND");
    }

    @Test
    void unexpectedErrorsBecomeTheGenericInternalError() throws Exception {
        JsonNode f = mapper.readTree(codec.errorFor(null, new IllegalStateException("db password is hunter2")));
        assertThat(f.get("code").asText()).isEqualTo("INTERNAL_ERROR");
        assertThat(f.get("message").asText()).isEqualTo("An unexpected error occurred");
    }

    @Test
    void ackAndMessageFramesCarryTheStoredFields() throws Exception {
        MessageResponse m = new MessageResponse(UUID.randomUUID(), UUID.fromString(CONV), 7, UUID.randomUUID(),
                UUID.fromString(CMID), "hi", Instant.parse("2026-11-04T10:15:30.123456Z"));
        JsonNode ack = mapper.readTree(codec.ack(m));
        assertThat(ack.get("v").asInt()).isEqualTo(1);
        assertThat(ack.get("type").asText()).isEqualTo("ack");
        assertThat(ack.get("messageId").asText()).isEqualTo(m.id().toString());
        assertThat(ack.get("seq").asLong()).isEqualTo(7);
        assertThat(ack.get("clientMsgId").asText()).isEqualTo(CMID);
        assertThat(Instant.parse(ack.get("createdAt").asText())).isEqualTo(m.createdAt());

        JsonNode msg = mapper.readTree(codec.message(m));
        assertThat(msg.get("type").asText()).isEqualTo("message");
        assertThat(msg.get("message").get("body").asText()).isEqualTo("hi");
    }

    @Test
    void validCursorIsParsed() {
        Parsed p = parse("{\"v\":1,\"type\":\"cursor\",\"conversationId\":\"" + CONV + "\",\"kind\":\"read\",\"seq\":42}");
        assertThat(p).isEqualTo(new Frames.CursorCommand(UUID.fromString(CONV), "read", 42));
    }

    @Test
    void malformedCursorFieldsAreRejectedWithNullClientMsgId() {
        for (String json : new String[]{
                "{\"v\":1,\"type\":\"cursor\",\"conversationId\":\"" + CONV + "\",\"kind\":\"read\",\"seq\":\"42\"}",
                "{\"v\":1,\"type\":\"cursor\",\"conversationId\":\"" + CONV + "\",\"kind\":\"read\",\"seq\":4.5}",
                "{\"v\":1,\"type\":\"cursor\",\"kind\":\"read\",\"seq\":1}",
                "{\"v\":1,\"type\":\"cursor\",\"conversationId\":\"" + CONV + "\",\"kind\":7,\"seq\":1}"}) {
            Parsed p = parse(json);
            assertThat(p).as(json).isInstanceOf(Rejected.class);
            assertThat(((Rejected) p).clientMsgId()).isNull();
            assertThat(((Rejected) p).code()).isEqualTo("VALIDATION_ERROR");
        }
    }

    @Test
    void cursorFrameCarriesBothCursors() throws Exception {
        UUID user = UUID.randomUUID();
        JsonNode f = mapper.readTree(codec.cursor(UUID.fromString(CONV), user, 12, 10));
        assertThat(f.get("type").asText()).isEqualTo("cursor");
        assertThat(f.get("userId").asText()).isEqualTo(user.toString());
        assertThat(f.get("lastDeliveredSeq").asLong()).isEqualTo(12);
        assertThat(f.get("lastReadSeq").asLong()).isEqualTo(10);
    }
}
