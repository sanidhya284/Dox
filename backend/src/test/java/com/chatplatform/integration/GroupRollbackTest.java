package com.chatplatform.integration;

import com.chatplatform.auth.dto.AuthResponse;
import com.chatplatform.auth.dto.RegisterRequest;
import com.chatplatform.conversation.dto.CreateGroupRequest;
import com.chatplatform.conversation.repository.ParticipantRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;

class GroupRollbackTest extends AbstractIntegrationTest {

    @SpyBean
    private ParticipantRepository participantRepository;

    @Autowired
    private JdbcClient jdbcClient;

    private AuthResponse register(String suffix) {
        RegisterRequest req = new RegisterRequest(
                "user_" + suffix,
                "user_" + suffix + "@example.com",
                "password123",
                "User " + suffix
        );
        ResponseEntity<AuthResponse> res = restTemplate.postForEntity("/api/auth/register", req, AuthResponse.class);
        return res.getBody();
    }

    private HttpHeaders bearer(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return h;
    }

    @Test
    void groupCreationRollsBackOnParticipantInsertFailure() {
        String s = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        AuthResponse a = register("rb_a_" + s);
        AuthResponse b = register("rb_b_" + s);
        AuthResponse c = register("rb_c_" + s);

        doThrow(new RuntimeException("Simulated db error"))
                .when(participantRepository).insertMultiple(any(UUID.class), anyList(), anyString());

        String uniqueTitle = "Rollback Group " + s;
        CreateGroupRequest req = new CreateGroupRequest(uniqueTitle, List.of(b.user().id(), c.user().id()));

        ResponseEntity<Object> res = restTemplate.postForEntity(
                "/api/conversations/group",
                new HttpEntity<>(req, bearer(a.accessToken())),
                Object.class
        );

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);

        Integer count = jdbcClient.sql("SELECT count(*) FROM conversations WHERE title = ?")
                .param(uniqueTitle)
                .query(Integer.class)
                .single();

        assertThat(count).isEqualTo(0);
    }
}
