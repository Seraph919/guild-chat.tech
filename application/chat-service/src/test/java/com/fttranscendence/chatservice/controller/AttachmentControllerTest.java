package com.fttranscendence.chatservice.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fttranscendence.chatservice.auth.AuthenticationFilter;
import com.fttranscendence.chatservice.exception.ApiException;
import com.fttranscendence.chatservice.grpc.IdentityClient;
import com.fttranscendence.chatservice.grpc.MediaClient;
import com.fttranscendence.chatservice.service.AttachmentService;

/** The HTTP surface of the upload-URL endpoint; the service is mocked. */
@WebMvcTest(AttachmentController.class)
@AutoConfigureMockMvc(addFilters = false)
class AttachmentControllerTest {

    private static final String USER = "a215c5f8-d810-4518-ad4c-8d551eb6dfc4";
    private static final String PATH = "/rooms/42/attachments/upload-url";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AttachmentService attachmentService;

    /** @WebMvcTest includes Filter beans, so AuthenticationFilter's dependency must exist. */
    @MockBean
    private IdentityClient identityClient;

    private static MockHttpServletRequestBuilder authenticatedPost(String json) {
        return post(PATH)
                .requestAttr(AuthenticationFilter.USER_ID_ATTRIBUTE, USER)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json);
    }

    @Test
    @DisplayName("201 with the upload ticket in snake_case")
    void returnsTicketInSnakeCase() throws Exception {
        when(attachmentService.uploadUrl(USER, 42L, "image/png")).thenReturn(
                new MediaClient.UploadTicket("upload-1", "https://r2/put", "image/png", 300));

        mockMvc.perform(authenticatedPost("{\"content_type\":\"image/png\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.upload_id").value("upload-1"))
                .andExpect(jsonPath("$.upload_url").value("https://r2/put"))
                .andExpect(jsonPath("$.content_type").value("image/png"))
                .andExpect(jsonPath("$.expires_in").value(300));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "{\"content_type\":\"\"}",
            "{\"content_type\":\"image/gif\"}",
            "{\"content_type\":\"image/svg+xml\"}",
            "{\"content_type\":\"image/png; charset=x\"}" })
    @DisplayName("a missing or unsupported type is a 400 VALIDATION_ERROR; media is not called")
    void rejectsUnsupportedTypes(String json) throws Exception {
        mockMvc.perform(authenticatedPost(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details.content_type").exists());
        verifyNoInteractions(attachmentService);
    }

    @Test
    @DisplayName("service errors keep their status and code (403 non-member, 503 media down)")
    void serviceErrorsPassThrough() throws Exception {
        when(attachmentService.uploadUrl(anyString(), anyLong(), any()))
                .thenThrow(ApiException.forbidden("NOT_A_MEMBER", "User is not a member of the room"))
                .thenThrow(new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "MEDIA_UNAVAILABLE",
                        "Image service is unavailable"));

        mockMvc.perform(authenticatedPost("{\"content_type\":\"image/png\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_A_MEMBER"));
        mockMvc.perform(authenticatedPost("{\"content_type\":\"image/png\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("MEDIA_UNAVAILABLE"));
    }
}
