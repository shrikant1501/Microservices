package com.microlearning.user;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microlearning.user.api.CreateUserRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Uses @EmbeddedKafka — starts an in-memory Kafka broker for tests.
 * Config server and Eureka disabled via test application.properties.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@EmbeddedKafka(partitions = 1, topics = {"user-created"},
               brokerProperties = {"listeners=PLAINTEXT://localhost:9093",
                                   "port=9093"})
class UserServiceIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper mapper;

    @Test
    @DisplayName("POST /api/users → creates user and returns 201")
    void createUser() throws Exception {
        var req = new CreateUserRequest();
        req.setName("Alice"); req.setEmail("alice@test.com");

        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id",    notNullValue()))
                .andExpect(jsonPath("$.name",  is("Alice")));
    }

    @Test
    @DisplayName("POST /api/users → 409 on duplicate email")
    void createUser_duplicate() throws Exception {
        var req = new CreateUserRequest();
        req.setName("Bob"); req.setEmail("bob@test.com");
        String body = mapper.writeValueAsString(req);

        mockMvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("GET /api/users/{id} → 404 for missing user")
    void getUser_notFound() throws Exception {
        mockMvc.perform(get("/api/users/9999")).andExpect(status().isNotFound());
    }
}
