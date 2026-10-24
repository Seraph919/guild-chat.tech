package com.fttranscendence.chatservice.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.fttranscendence.chatservice.auth.AuthenticationFilter;
import com.fttranscendence.chatservice.dto.AttachmentResponse;
import com.fttranscendence.chatservice.exception.ApiException;
import com.fttranscendence.chatservice.grpc.IdentityClient;
import com.fttranscendence.chatservice.model.Attachment;
import com.fttranscendence.chatservice.model.Message;
import com.fttranscendence.chatservice.service.AttachmentUrls;
import com.fttranscendence.chatservice.service.MessageService;

/**
 * The HTTP surface only: status codes, JSON shape, error envelope.
 * The service is mocked, so nothing here touches MongoDB.
 */
@WebMvcTest(MessageController.class)
@AutoConfigureMockMvc(addFilters = false) // the filter has its own test; this slice is the controller
class MessageControllerTest {

    private static final long ROOM = 42L;
    private static final String SENDER = "a215c5f8-d810-4518-ad4c-8d551eb6dfc4";

    /**
     * A @WebMvcTest slice does not run the servlet filter chain, so the
     * attribute the filter would publish is set by hand here. The filter itself
     * is covered by AuthenticationFilterTest.
     */
    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
            authenticatedGet(String path) {
        return get(path).requestAttr(AuthenticationFilter.USER_ID_ATTRIBUTE, SENDER);
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
            authenticatedPost(String path, String json) {
        return post(path)
                .requestAttr(AuthenticationFilter.USER_ID_ATTRIBUTE, SENDER)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json);
    }

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private MessageService messageService;

    /** @WebMvcTest includes Filter beans, so AuthenticationFilter's dependency must exist. */
    @MockBean
    private IdentityClient identityClient;

    /** Signs every image with a fixed URL, so the controller's use of it is visible. */
    @MockBean
    private AttachmentUrls attachmentUrls;

    @BeforeEach
    void signImages() {
        when(attachmentUrls.sign(any())).thenAnswer(i -> AttachmentResponse.from(
                i.getArgument(0), "https://r2/signed", Instant.parse("2026-09-20T13:01:51Z")));
    }

    private Message message(String content) {
        return Message.create(ROOM, SENDER, null, content, "text",
                Instant.parse("2026-09-20T12:56:51.179Z"));
    }

    @Test
    @DisplayName("returns the history as a JSON array in snake_case")
    void returnsHistoryInSnakeCase() throws Exception {
        when(messageService.history(eq(SENDER), eq(ROOM), any(), any())).thenReturn(List.of(message("hello")));

        mockMvc.perform(authenticatedGet("/rooms/42/messages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].content").value("hello"))
                .andExpect(jsonPath("$[0].room_id").value(42))
                .andExpect(jsonPath("$[0].sender_id").value(SENDER))
                .andExpect(jsonPath("$[0].content_type").value("text"))
                .andExpect(jsonPath("$[0].created_at").exists())
                .andExpect(jsonPath("$[0].deleted").value(false))
                // camelCase must not leak into the API
                .andExpect(jsonPath("$[0].roomId").doesNotExist())
                // deleted_at is internal and never exposed
                .andExpect(jsonPath("$[0].deleted_at").doesNotExist());
    }

    @Test
    @DisplayName("a deleted message keeps its place but hides its content")
    void deletedMessageHidesContent() throws Exception {
        Message deleted = message("secret");
        deleted.markDeleted(Instant.parse("2026-09-20T13:00:00Z"));
        when(messageService.history(eq(SENDER), eq(ROOM), any(), any())).thenReturn(List.of(deleted));

        mockMvc.perform(authenticatedGet("/rooms/42/messages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].deleted").value(true))
                .andExpect(jsonPath("$[0].content").doesNotExist());
    }

    @Test
    @DisplayName("passes before and limit through to the service")
    void passesQueryParametersThrough() throws Exception {
        when(messageService.history(eq(SENDER), eq(ROOM), any(), any())).thenReturn(List.of());

        mockMvc.perform(authenticatedGet("/rooms/42/messages")
                        .param("before", "6a75d60a586b1c7be1750ad3")
                        .param("limit", "10"))
                .andExpect(status().isOk());

        ArgumentCaptor<String> before = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
        org.mockito.Mockito.verify(messageService)
                .history(eq(SENDER), eq(ROOM), before.capture(), limit.capture());

        org.assertj.core.api.Assertions.assertThat(before.getValue())
                .isEqualTo("6a75d60a586b1c7be1750ad3");
        org.assertj.core.api.Assertions.assertThat(limit.getValue()).isEqualTo(10);
    }

    @Test
    @DisplayName("an ApiException comes back in the spec's error envelope")
    void errorsUseTheSpecEnvelope() throws Exception {
        when(messageService.history(eq(SENDER), eq(ROOM), any(), any()))
                .thenThrow(new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CURSOR",
                        "'before' is not a valid message id"));

        mockMvc.perform(authenticatedGet("/rooms/42/messages").param("before", "junk"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_CURSOR"))
                .andExpect(jsonPath("$.error.message").value("'before' is not a valid message id"));
    }

    @Test
    @DisplayName("a non-numeric room id is a 400 in the envelope, not a 500")
    void nonNumericRoomIdIsBadRequest() throws Exception {
        mockMvc.perform(authenticatedGet("/rooms/not-a-number/messages"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_PARAMETER"));
    }

    @Test
    @DisplayName("an unexpected failure returns 500 without leaking internals")
    void unexpectedFailureIsOpaque() throws Exception {
        when(messageService.history(eq(SENDER), eq(ROOM), any(), any()))
                .thenThrow(new IllegalStateException("mongo credentials rejected at 10.0.0.5"));

        mockMvc.perform(authenticatedGet("/rooms/42/messages"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.error.message").value("Unexpected error"));
    }

    @Test
    @DisplayName("no authenticated user attribute: the request fails closed and never reaches the service")
    void requiresTheAuthenticatedUserAttribute() throws Exception {
        // In production AuthenticationFilter always sets the attribute, so this
        // can only happen through a misconfiguration. Spring cannot bind the
        // required parameter and the request is rejected as a 500 before the
        // controller body runs. The status is unhelpful, but the important
        // property holds: it fails CLOSED — no history is returned.
        mockMvc.perform(get("/rooms/42/messages"))
                .andExpect(status().is5xxServerError());

        org.mockito.Mockito.verifyNoInteractions(messageService);
    }

    @Test
    @DisplayName("history images come back with signed URLs")
    void historySignsImages() throws Exception {
        Message withImage = Message.create(ROOM, SENDER, null, null, "text",
                Instant.parse("2026-09-20T12:56:51.179Z"),
                List.of(new Attachment("m1", SENDER, "image/webp", 1600, 1200)));
        when(messageService.history(eq(SENDER), eq(ROOM), any(), any())).thenReturn(List.of(withImage));

        mockMvc.perform(authenticatedGet("/rooms/42/messages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].attachments[0].media_id").value("m1"))
                .andExpect(jsonPath("$[0].attachments[0].url").value("https://r2/signed"));
    }

    // ------------------------------------------------- POST /rooms/:id/messages

    @Test
    @DisplayName("sending returns 201 with the created message in snake_case")
    void sendReturnsCreated() throws Exception {
        when(messageService.send(eq(SENDER), eq(ROOM), eq("hello"), any(), any()))
                .thenReturn(message("hello"));

        mockMvc.perform(authenticatedPost("/rooms/42/messages", "{\"content\":\"hello\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.content").value("hello"))
                .andExpect(jsonPath("$.room_id").value(42))
                .andExpect(jsonPath("$.sender_id").value(SENDER))
                .andExpect(jsonPath("$.created_at").exists());
    }

    @Test
    @DisplayName("reply_to_id from the body reaches the service")
    void sendPassesReplyToId() throws Exception {
        when(messageService.send(eq(SENDER), eq(ROOM), eq("me too"), eq("6a75d60a586b1c7be1750ad3"), any()))
                .thenReturn(message("me too"));

        mockMvc.perform(authenticatedPost("/rooms/42/messages",
                        "{\"content\":\"me too\",\"reply_to_id\":\"6a75d60a586b1c7be1750ad3\"}"))
                .andExpect(status().isCreated());

        org.mockito.Mockito.verify(messageService)
                .send(SENDER, ROOM, "me too", "6a75d60a586b1c7be1750ad3", null);
    }

    @Test
    @DisplayName("a sender_id in the body is ignored: the sender always comes from the token")
    void senderIdInBodyIsIgnored() throws Exception {
        when(messageService.send(eq(SENDER), eq(ROOM), eq("spoof"), any(), any()))
                .thenReturn(message("spoof"));

        mockMvc.perform(authenticatedPost("/rooms/42/messages",
                        "{\"content\":\"spoof\",\"sender_id\":\"11111111-1111-1111-1111-111111111111\"}"))
                .andExpect(status().isCreated());

        // the service is called with the authenticated user, not the body value
        org.mockito.Mockito.verify(messageService).send(SENDER, ROOM, "spoof", null, null);
    }

    @Test
    @DisplayName("text is optional in the body: \"no text and no image\" is the service's EMPTY_MESSAGE")
    void emptyMessageIsRejectedByService() throws Exception {
        when(messageService.send(eq(SENDER), eq(ROOM), any(), any(), any()))
                .thenThrow(ApiException.badRequest("EMPTY_MESSAGE", "A message needs text or an image"));

        mockMvc.perform(authenticatedPost("/rooms/42/messages", "{\"content\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("EMPTY_MESSAGE"));
        mockMvc.perform(authenticatedPost("/rooms/42/messages", "{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("EMPTY_MESSAGE"));
    }

    @Test
    @DisplayName("attachment_upload_ids reach the service; the message comes back with attachments")
    void sendPassesUploadIdsAndReturnsAttachments() throws Exception {
        String upload = "0b7f3c1e-1d2a-4c5b-9e8f-7a6b5c4d3e2f";
        Message withImage = Message.create(ROOM, SENDER, null, null, "text",
                Instant.parse("2026-09-20T12:56:51.179Z"),
                List.of(new Attachment("5d3c0000-0000-4000-8000-000000000001", SENDER, "image/webp", 1600, 1200)));
        when(messageService.send(SENDER, ROOM, null, null, List.of(upload))).thenReturn(withImage);

        mockMvc.perform(authenticatedPost("/rooms/42/messages",
                        "{\"attachment_upload_ids\":[\"" + upload + "\"]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.content").doesNotExist())
                .andExpect(jsonPath("$.attachments[0].media_id").value("5d3c0000-0000-4000-8000-000000000001"))
                .andExpect(jsonPath("$.attachments[0].content_type").value("image/webp"))
                .andExpect(jsonPath("$.attachments[0].width").value(1600))
                .andExpect(jsonPath("$.attachments[0].height").value(1200))
                .andExpect(jsonPath("$.attachments[0].url").value("https://r2/signed"))
                .andExpect(jsonPath("$.attachments[0].url_expires_at").value("2026-09-20T13:01:51Z"))
                // owner_id is internal: media needs it, the browser does not
                .andExpect(jsonPath("$.attachments[0].owner_id").doesNotExist());
    }

    @Test
    @DisplayName("a text message has an empty attachments array, never null")
    void textMessageHasEmptyAttachments() throws Exception {
        when(messageService.send(eq(SENDER), eq(ROOM), eq("hello"), any(), any())).thenReturn(message("hello"));

        mockMvc.perform(authenticatedPost("/rooms/42/messages", "{\"content\":\"hello\"}"))
                .andExpect(jsonPath("$.attachments").isArray())
                .andExpect(jsonPath("$.attachments").isEmpty());
    }

    @Test
    @DisplayName("more than 4 images, or an id that is not an upload_id, is a 400 before the service")
    void invalidUploadIdsAreRejected() throws Exception {
        String id = "\"0b7f3c1e-1d2a-4c5b-9e8f-7a6b5c4d3e2f\"";
        String five = String.join(",", id, id, id, id, id);

        mockMvc.perform(authenticatedPost("/rooms/42/messages", "{\"attachment_upload_ids\":[" + five + "]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                // details name the JSON field, snake_case like the request
                .andExpect(jsonPath("$.error.details.attachment_upload_ids").exists())
                .andExpect(jsonPath("$.error.details.attachmentUploadIds").doesNotExist());
        mockMvc.perform(authenticatedPost("/rooms/42/messages", "{\"attachment_upload_ids\":[\"../etc\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details['attachment_upload_ids[0]']").value("must be an upload_id"));

        org.mockito.Mockito.verifyNoInteractions(messageService);
    }

    @Test
    @DisplayName("content over 4000 characters is rejected before reaching the service")
    void oversizedContentIsRejected() throws Exception {
        String tooLong = "x".repeat(4001);

        mockMvc.perform(authenticatedPost("/rooms/42/messages", "{\"content\":\"" + tooLong + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

        org.mockito.Mockito.verifyNoInteractions(messageService);
    }

    @Test
    @DisplayName("exactly 4000 characters is allowed (boundary)")
    void maximumContentIsAccepted() throws Exception {
        String atLimit = "x".repeat(4000);
        when(messageService.send(eq(SENDER), eq(ROOM), eq(atLimit), any(), any())).thenReturn(message(atLimit));

        mockMvc.perform(authenticatedPost("/rooms/42/messages", "{\"content\":\"" + atLimit + "\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("a service rejection keeps the spec envelope (403 for a non-member)")
    void serviceRejectionUsesEnvelope() throws Exception {
        when(messageService.send(eq(SENDER), eq(ROOM), any(), any(), any()))
                .thenThrow(new ApiException(HttpStatus.FORBIDDEN, "NOT_A_MEMBER",
                        "User is not a member of the room"));

        mockMvc.perform(authenticatedPost("/rooms/42/messages", "{\"content\":\"hi\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_A_MEMBER"));
    }
}
