package com.microlearning.todo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microlearning.todo.api.CreateTodoRequest;
import com.microlearning.todo.client.UserResponse;
import com.microlearning.todo.client.UserServiceClient;
import feign.FeignException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * TodoServiceIntegrationTest
 *
 * MICROSERVICES TESTING INSIGHT:
 * We cannot start a real user-service in a unit/integration test.
 * So we use @MockBean to replace the Feign client with a Mockito mock.
 *
 * This tests todo-service IN ISOLATION:
 *   - TodoController, TodoService, TodoRepository run against real H2
 *   - UserServiceClient is mocked — we control what it returns
 *
 * This is the "Service in Isolation" testing pattern.
 * Phase 13 covers Consumer-Driven Contract Testing (Pact) which provides
 * a stronger guarantee that the mock matches the real user-service response.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class TodoServiceIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper mapper;

    /** Replace the real Feign HTTP client with a mock for tests */
    @MockBean
    UserServiceClient userServiceClient;

    @Test
    @DisplayName("POST /api/todos → creates todo when user exists")
    void createTodo_success() throws Exception {
        // ARRANGE: mock user-service to return a valid user
        UserResponse mockUser = new UserResponse();
        mockUser.setId(1L); mockUser.setName("Alice"); mockUser.setEmail("alice@test.com");
        Mockito.when(userServiceClient.getUserById(1L)).thenReturn(mockUser);

        // ACT
        var req = new CreateTodoRequest();
        req.setTitle("Write tests"); req.setUserId(1L);

        mockMvc.perform(post("/api/todos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(req)))
                // ASSERT
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title",    is("Write tests")))
                .andExpect(jsonPath("$.userName", is("Alice")))       // snapshot stored
                .andExpect(jsonPath("$.userId",   is(1)))
                .andExpect(jsonPath("$.completed",is(false)));
    }

    @Test
    @DisplayName("POST /api/todos → 404 when user does not exist in user-service")
    void createTodo_userNotFound() throws Exception {
        // ARRANGE: user-service returns 404 → Feign throws FeignException.NotFound
        Mockito.when(userServiceClient.getUserById(99L))
               .thenThrow(FeignException.NotFound.class);

        var req = new CreateTodoRequest();
        req.setTitle("Will fail"); req.setUserId(99L);

        mockMvc.perform(post("/api/todos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(req)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("PATCH /api/todos/{id}/complete → marks todo as completed")
    void completeTodo() throws Exception {
        // First create
        UserResponse mockUser = new UserResponse();
        mockUser.setId(1L); mockUser.setName("Bob"); mockUser.setEmail("bob@test.com");
        Mockito.when(userServiceClient.getUserById(1L)).thenReturn(mockUser);

        var req = new CreateTodoRequest();
        req.setTitle("Buy groceries"); req.setUserId(1L);

        String createResult = mockMvc.perform(post("/api/todos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        Long createdId = mapper.readTree(createResult).get("id").asLong();

        // Then complete
        mockMvc.perform(patch("/api/todos/" + createdId + "/complete"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completed",    is(true)))
                .andExpect(jsonPath("$.completedAt",  notNullValue()));
    }
}
