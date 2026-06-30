package com.microlearning.todo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microlearning.todo.api.CreateTodoRequest;
import com.microlearning.todo.api.TodoResponse;
import com.microlearning.todo.api.TodoService;
import com.microlearning.todo.client.UserResponse;
import com.microlearning.todo.client.UserServiceClient;
import com.microlearning.todo.domain.OutboxEventRepository;
import com.microlearning.todo.domain.Todo;
import com.microlearning.todo.domain.TodoRepository;
import feign.FeignException;
import feign.Request;
import feign.Request.HttpMethod;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * TodoServiceUnitTest — Phase 13: Pure unit test, zero Spring context.
 *
 * ═══════════════════════════════════════════════════════════════
 * WHY THIS IS DIFFERENT FROM TodoServiceIntegrationTest
 * ═══════════════════════════════════════════════════════════════
 *
 * TodoServiceIntegrationTest (@SpringBootTest):
 *   - Starts full Spring context: ~10-15 seconds
 *   - Real H2 database
 *   - Tests the FULL stack (controller → service → repository → DB)
 *   - Used for: integration/regression testing
 *
 * THIS test (no @SpringBootTest):
 *   - No Spring context: ~50 milliseconds startup
 *   - All dependencies are Mockito mocks
 *   - Tests ONLY the business logic in TodoService
 *   - Used for: fast feedback on pure logic changes
 *
 * INTERVIEW QUESTION: "When would you use @MockBean vs @Mock?"
 *   @Mock (Mockito, this test):  NOT in Spring context. Manual instantiation.
 *   @MockBean (@SpringBootTest): Replaces a Spring bean. Context must start.
 *
 * RULE OF THUMB:
 *   Business logic tests → unit test (@Mock, no @SpringBootTest)
 *   Full request path tests → integration test (@SpringBootTest + @MockBean)
 */
class TodoServiceUnitTest {

    // All dependencies are plain Mockito mocks — no Spring involved
    private TodoRepository todoRepository;
    private UserServiceClient userServiceClient;
    private OutboxEventRepository outboxEventRepository;
    private TodoService todoService;

    @BeforeEach
    void setUp() {
        todoRepository         = mock(TodoRepository.class);
        userServiceClient      = mock(UserServiceClient.class);
        outboxEventRepository  = mock(OutboxEventRepository.class);
        // Directly instantiate the class under test — no @Autowired, no context
        todoService = new TodoService(todoRepository, userServiceClient,
                outboxEventRepository, new ObjectMapper());
    }

    // ─── createTodo: happy path ────────────────────────────────────────────────

    @Test
    @DisplayName("createTodo — stores userName from user-service on the todo")
    void createTodo_storesUserNameSnapshot() {
        // GIVEN: user-service returns Alice
        UserResponse alice = new UserResponse();
        alice.setId(1L);
        alice.setName("Alice");
        alice.setEmail("alice@test.com");
        when(userServiceClient.getUserById(1L)).thenReturn(alice);

        // GIVEN: repository saves and returns the todo
        // No setId() — id is JPA-generated. Use ReflectionTestUtils to set it in tests.
        Todo persisted = new Todo();
        ReflectionTestUtils.setField(persisted, "id", 10L);
        persisted.setTitle("Learn Microservices");
        persisted.setUserId(1L);
        persisted.setUserName("Alice");         // snapshot stored on the entity
        when(todoRepository.save(any(Todo.class))).thenReturn(persisted);

        // WHEN
        CreateTodoRequest req = new CreateTodoRequest();
        req.setTitle("Learn Microservices");
        req.setUserId(1L);
        TodoResponse response = todoService.createTodo(req);

        // THEN: the response carries the name snapshot
        assertThat(response.getUserName()).isEqualTo("Alice");
        assertThat(response.getId()).isEqualTo(10L);
        assertThat(response.getTitle()).isEqualTo("Learn Microservices");
    }

    // ─── createTodo: user not found ───────────────────────────────────────────

    @Test
    @DisplayName("createTodo — throws RuntimeException when user-service returns 404")
    void createTodo_userNotFound_throwsException() {
        // GIVEN: user-service returns 404
        // FeignException.NotFound cannot be mocked (Mockito inline limitation with Java 25).
        // Instead we create a real FeignException.NotFound using its static factory.
        Request dummyRequest = Request.create(
                HttpMethod.GET, "/api/users/99",
                Collections.emptyMap(), null, StandardCharsets.UTF_8, null);
        FeignException notFound = FeignException.errorStatus(
                "getUserById", feign.Response.builder()
                        .status(404).reason("Not Found")
                        .request(dummyRequest)
                        .headers(Collections.emptyMap())
                        .build());
        when(userServiceClient.getUserById(99L)).thenThrow(notFound);

        CreateTodoRequest req = new CreateTodoRequest();
        req.setTitle("Orphan todo");
        req.setUserId(99L);

        // WHEN + THEN: RuntimeException with user-not-found message
        assertThatThrownBy(() -> todoService.createTodo(req))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("User not found: 99");
    }

    // ─── getTodoById ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("getTodoById — returns existing todo without calling user-service")
    void getTodoById_returnsLocalData_noUserServiceCall() {
        // GIVEN: todo exists in repository
        Todo stored = new Todo();
        ReflectionTestUtils.setField(stored, "id", 5L);
        stored.setTitle("Stored todo");
        stored.setUserId(2L);
        stored.setUserName("Bob");    // snapshot from creation time
        when(todoRepository.findById(5L)).thenReturn(Optional.of(stored));

        // WHEN
        TodoResponse response = todoService.getTodoById(5L);

        // THEN: returns local data — userServiceClient never called
        assertThat(response.getId()).isEqualTo(5L);
        assertThat(response.getUserName()).isEqualTo("Bob");

        // KEY ASSERTION: zero calls to user-service
        // This proves read operations are resilient even when user-service is down
        org.mockito.Mockito.verifyNoInteractions(userServiceClient);
    }

    @Test
    @DisplayName("getTodoById — throws RuntimeException when todo does not exist")
    void getTodoById_notFound_throwsException() {
        when(todoRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> todoService.getTodoById(999L))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Todo not found: 999");
    }
}
