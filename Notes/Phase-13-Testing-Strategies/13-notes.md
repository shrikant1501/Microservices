# Phase 13 — Testing Microservices
## (Unit · Integration · Contract · End-to-End · TestContainers)

> **80/20 Focus:** "How do you test a microservice in isolation?" and "What is contract testing?" are asked at every senior interview. Master those two, and you're ahead of 90% of candidates.

---

## 1. The Problem

Testing a monolith is straightforward:
```
@SpringBootTest → one context → all beans available → MockMvc → done.
```

Testing microservices:
```
todo-service tests → needs user-service to be running?
                   → needs Kafka to be running?
                   → needs Eureka to be running?
                   → needs the DB to be running?

If YES → tests are slow, brittle, environment-dependent.
If NO  → how do you prove your Feign client matches user-service's actual API?
```

This is the core tension: **isolation vs confidence**.

---

## 2. The Testing Pyramid for Microservices

```
                        ┌─────────────┐
                        │  E2E Tests  │  ← Fewest. Full system. Very slow.
                        │  (few)      │    docker compose up + test
                        └──────┬──────┘
                    ┌──────────┴──────────┐
                    │  Contract Tests     │  ← Per service pair. Catches API drift.
                    │  (per integration)  │    Pact / Spring Cloud Contract
                    └──────────┬──────────┘
              ┌────────────────┴────────────────┐
              │  Integration Tests              │  ← Per service. Test one service
              │  (per service)                  │    with real DB (H2/Testcontainers)
              │                                 │    mocked dependencies.
              └────────────────┬────────────────┘
     ┌──────────────────────────┴───────────────────────────┐
     │  Unit Tests                                          │  ← Most. Fast. Pure logic.
     │  (most)                                              │    No Spring context.
     └──────────────────────────────────────────────────────┘
```

**Key shift from monolith testing:**
- Contract tests are NEW — they don't exist in monolith testing
- Integration tests scope is smaller (one service, not the whole app)
- E2E tests are fewer and run less frequently (CI/CD pipeline, not every commit)

---

## 3. Layer 1: Unit Tests

### What to unit test in microservices
- **Business logic** in service classes
- **Domain model** validation
- **Utility classes**, transformers, validators

### What NOT to unit test
- Controller mappings (test with MockMvc instead)
- Repository queries (test with integration test + real DB)
- Feign client calls (mock the Feign interface)

### Example: TodoService unit test (pure logic)

```java
class TodoServiceUnitTest {

    // No @SpringBootTest — no Spring context — instant startup
    private TodoService service;
    private TodoRepository todoRepository;
    private UserServiceClient userServiceClient;

    @BeforeEach
    void setup() {
        todoRepository    = Mockito.mock(TodoRepository.class);
        userServiceClient = Mockito.mock(UserServiceClient.class);
        service = new TodoService(todoRepository, userServiceClient, ...);
    }

    @Test
    void createTodo_setsUserNameFromUserService() {
        // Given
        UserResponse user = new UserResponse();
        user.setId(1L); user.setName("Alice");
        Mockito.when(userServiceClient.getUserById(1L)).thenReturn(user);

        Todo saved = new Todo();
        saved.setId(10L); saved.setTitle("Buy milk"); saved.setUserId(1L);
        Mockito.when(todoRepository.save(any())).thenReturn(saved);

        // When
        CreateTodoRequest req = new CreateTodoRequest();
        req.setTitle("Buy milk"); req.setUserId(1L);
        TodoResponse result = service.createTodo(req);

        // Then
        assertThat(result.getUserName()).isEqualTo("Alice");
        assertThat(result.getTitle()).isEqualTo("Buy milk");
    }
}
```

**Startup time:** ~50ms. Run hundreds of these.

---

## 4. Layer 2: Integration Tests (Per Service)

### What is a service integration test?
Start the **full Spring context** for ONE service, with:
- **Real in-memory DB** (H2 or Testcontainers PostgreSQL)
- **Mocked dependencies** (Feign clients, Kafka — or embedded Kafka)
- **Real HTTP endpoints** via MockMvc

This is what our existing `TodoServiceIntegrationTest` does — it's already a good integration test.

### The `@SpringBootTest` + `@MockBean` pattern

```java
@SpringBootTest           // Starts the FULL Spring context for todo-service
@AutoConfigureMockMvc     // Sets up MockMvc for HTTP testing
class TodoServiceIntegrationTest {

    @MockBean
    UserServiceClient userServiceClient;  // Replaced with a Mockito mock
    // UserServiceClient is still in the Spring context — but it's a mock.
    // Feign never makes a real HTTP call. We control its behaviour.

    @Test
    void createTodo_success() {
        Mockito.when(userServiceClient.getUserById(1L))
               .thenReturn(mockUser("Alice"));
        // ... test with real DB (H2), real service layer, real controller
    }
}
```

**Why this is correct:**
- Tests the FULL request path: controller → service → repository → DB
- Feign client is mocked → no dependency on user-service running
- DB is real (H2) → catches SQL mapping errors, constraint violations

### Testcontainers — Real DB in Tests

For services that will use PostgreSQL in production, H2 dialect differences can hide bugs. Testcontainers starts a **real PostgreSQL container** during tests:

```java
@SpringBootTest
@Testcontainers   // JUnit 5 extension that manages container lifecycle
class TodoServicePostgresTest {

    @Container
    static PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("todosdb")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource  // Injects the container's URL into Spring context
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    // Now all tests run against a real PostgreSQL instance
    // Container starts once per test class (static field)
    // Container is stopped and removed after the test class
}
```

**Testcontainers for Kafka:**
```java
@Container
static KafkaContainer kafka =
    new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.5.0"));

@DynamicPropertySource
static void kafkaProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
}
```

---

## 5. Layer 3: Contract Testing ← THE KEY CONCEPT

### The Problem Contract Testing Solves

```
user-service exposes:  GET /api/users/{id} → { "id": 1, "name": "Alice", "email": "..." }
todo-service consumes: UserResponse { Long id; String name; String email; }

Scenario: user-service team renames "name" to "fullName" in their response.
          user-service tests: PASS (they updated their tests)
          todo-service tests: PASS (they mock userServiceClient — no real call)
          Production:         BOOM — todo-service gets null for userName
```

**Integration tests don't catch this** because both sides mock their dependencies.
**E2E tests catch this** but only if you run the full system — slow and expensive.

**Contract testing** catches this at CI time, without running the full system.

### What is a Contract?

A contract is a formal agreement between a **Consumer** (todo-service) and a **Provider** (user-service) about what the API looks like.

```
Contract: "When todo-service calls GET /api/users/1, 
           user-service MUST return a JSON body with:
           - id: a Long
           - name: a non-null String
           - email: a non-null String"
```

### Consumer-Driven Contract Testing (CDC)

The consumer **drives** the contract:
1. todo-service (consumer) writes the contract: "here's what I need from you"
2. Contract is published to a broker (Pact Broker or Spring Cloud Contract)
3. user-service (provider) runs the contract: "can I satisfy this contract?"
4. If user-service renames `name` to `fullName` → contract verification FAILS → CI blocks the deploy

This is far superior to provider-driven contracts because:
- Consumer knows exactly what fields it uses (the provider may return 20 fields but consumer uses 3)
- The contract is minimal — only what the consumer needs

```
WITHOUT CONTRACT TESTING:
  Consumer test: mock provider → can't detect API change
  Provider test: tests own API → doesn't know what consumers need

WITH CONTRACT TESTING:
  Consumer generates contract from its test
  Provider verifies it satisfies all consumer contracts
  API change that breaks a consumer → caught immediately
```

### Spring Cloud Contract (our stack)

Spring Cloud Contract is the Spring-native approach. The contract is written in Groovy DSL or YAML and lives in the **provider's** codebase.

**Contract file** (in `user-service/src/test/resources/contracts/`):
```groovy
// get_user_by_id.groovy
org.springframework.cloud.contract.spec.Contract.make {
    description "should return user when user exists"

    request {
        method GET()
        url "/api/users/1"
    }

    response {
        status OK()
        headers {
            contentType(applicationJson())
        }
        body([
            id   : 1,
            name : "Alice",
            email: "alice@example.com"
        ])
    }
}
```

**What Spring Cloud Contract generates from this:**
1. **On provider side (user-service):** Auto-generates a test that verifies user-service actually returns this response
2. **On consumer side (todo-service):** Auto-generates a WireMock stub so todo-service can test against a realistic fake

---

## 6. Layer 4: End-to-End Tests

Run against the **full running system** (Docker Compose environment). Test user journeys, not individual services.

```java
// E2E test — runs against full system
@Test
void createUserAndTodo_endToEnd() {
    // 1. Create user via API Gateway
    var user = RestAssured.given()
        .baseUri("http://localhost:8080")
        .contentType(ContentType.JSON)
        .body("""{ "name": "Alice", "email": "alice@test.com" }""")
        .header("Authorization", "Bearer " + getTestJwt())
        .post("/api/users")
        .then().statusCode(201)
        .extract().as(UserResponse.class);

    // 2. Create todo for that user
    var todo = RestAssured.given()
        .baseUri("http://localhost:8080")
        .contentType(ContentType.JSON)
        .body("""{ "title": "Buy milk", "userId": """ + user.getId() + "}")
        .header("Authorization", "Bearer " + getTestJwt())
        .post("/api/todos")
        .then().statusCode(201)
        .extract().as(TodoResponse.class);

    assertThat(todo.getUserName()).isEqualTo("Alice");
}
```

**When to run E2E tests:**
- NOT on every commit (too slow)
- On merge to main branch in CI/CD
- Nightly against staging environment

---

## 7. Testing Kafka Consumers

### Strategy 1: @EmbeddedKafka (Fast, no Docker needed)

```java
@SpringBootTest
@EmbeddedKafka(
    partitions = 1,
    topics = { "user-events" }
)
class NotificationConsumerTest {

    @Autowired KafkaTemplate<String, String> kafkaTemplate;
    @Autowired NotificationConsumer consumer; // or check side effects

    @Test
    void consumer_processesUserCreatedEvent() throws Exception {
        String event = """{"userId":1,"email":"alice@test.com","eventType":"USER_CREATED"}""";

        kafkaTemplate.send("user-events", event);

        // Wait for async consumer — use Awaitility
        Awaitility.await()
            .atMost(Duration.ofSeconds(5))
            .untilAsserted(() ->
                verify(notificationService, times(1)).sendWelcomeEmail("alice@test.com")
            );
    }
}
```

### Strategy 2: Testcontainers Kafka (Closer to production)

```java
@Container
static KafkaContainer kafka =
    new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.5.0"));
```

**Use @EmbeddedKafka for unit/integration tests.** Use Testcontainers Kafka for E2E.

---

## 8. What We Already Have vs What's Added in Phase 13

### Already implemented (Phases 1–11):
```
TodoServiceIntegrationTest     — @SpringBootTest + @MockBean + MockMvc  ✅
UserServiceIntegrationTest     — @SpringBootTest + @EmbeddedKafka       ✅
ApiGatewayTest                 — @SpringBootTest + WebTestClient        ✅
```

### What Phase 13 adds:
```
TodoServiceUnitTest            — Pure unit test, no Spring context       ← NEW
ContractTest concept           — Contract DSL explanation, no full Pact setup
```

---

## 9. Common Mistakes

| Mistake | Problem | Fix |
|---|---|---|
| `@SpringBootTest` for every test | 30s startup for a 5ms logic test | Use pure unit tests for business logic |
| Mocking the repository in `@SpringBootTest` | You're not testing the DB layer | Use `@DataJpaTest` for repository layer |
| Not using `@Transactional` in integration tests | Test data pollutes other tests | Add `@Transactional` — Spring rolls back after each test |
| Not testing the fallback | You know CB opens, but fallback untested | Test what the fallback returns — it's customer-facing |
| Using `Thread.sleep()` for async | Brittle, slow, random failures | Use `Awaitility.await().untilAsserted(...)` |
| Testing implementation, not behaviour | Tests break on refactors | Test the HTTP API (input/output), not internal method calls |

---

## 10. Production Best Practices

```
1. TEST PYRAMID DISCIPLINE
   → 70% unit tests (fast, pure logic)
   → 20% integration tests (per service, real DB)
   → 10% contract + E2E tests (cross-service)

2. TEST ISOLATION
   → Each test must be independent — random order should work
   → @Transactional rollback OR @DirtiesContext to reset state
   → Use random data (UUID-based) to avoid conflicts

3. CI/CD TEST STAGES
   Stage 1 (every commit): unit tests + fast integration tests (~2 min)
   Stage 2 (merge to main): contract tests + slow integration tests (~10 min)
   Stage 3 (nightly/staging): E2E tests against deployed system (~30 min)

4. TESTCONTAINERS IN CI
   → Works in Docker-in-Docker (GitHub Actions, GitLab CI)
   → Use Ryuk (TestContainers resource reaper) for cleanup
   → Reuse containers across test classes with @Testcontainers + static fields

5. CONTRACT TEST WORKFLOW
   Consumer writes contract → Consumer CI publishes to Pact Broker
   Provider CI fetches contracts → Provider verifies → Can-I-Deploy check
   Only deploy if can-i-deploy passes for all consumers
```

---

## 11. Frequently Asked Interview Questions

**Q1: How do you test a microservice that depends on 3 other services?**
> You don't start the other 3 services. You mock the Feign client interfaces using `@MockBean` in your integration test. The test starts the full Spring context for the service under test (with a real in-memory DB), but all outbound HTTP calls go to Mockito mocks. This tests the full request path within the service while being completely independent of other services running.

**Q2: What is Contract Testing and why does it exist?**
> Contract testing solves the gap between unit/integration tests and full E2E tests. Each service tests in isolation — consumers mock providers. This means a provider can change its API and break consumers, and no test will catch it until E2E. A contract is a formal specification of the API interaction. The consumer writes what it needs, the provider proves it can satisfy that. When the provider changes its API, contract verification fails at CI time — not in production. Tools: Pact (language-agnostic), Spring Cloud Contract (Spring-native).

**Q3: What is the difference between `@SpringBootTest` and `@WebMvcTest`?**
> `@SpringBootTest` starts the full application context — all beans, DB, Kafka, etc. It's comprehensive but slow (~10–30 seconds). `@WebMvcTest` starts only the web layer (controllers, filters, ControllerAdvice) — no service layer, no repository, no DB. It's fast (~2 seconds). Use `@WebMvcTest` to test controller request mapping, validation, and response format; use `@SpringBootTest` to test the full stack.

**Q4: How do you test Kafka consumers?**
> Two approaches: `@EmbeddedKafka` starts an in-memory Kafka broker using the same Kafka client library — fast, no Docker needed, works everywhere. Testcontainers starts a real Kafka Docker container — slower, but identical to production. For unit/integration tests: `@EmbeddedKafka`. For staging/E2E: Testcontainers. Always use `Awaitility` for async assertions — never `Thread.sleep()`.

**Q5: What is `@DataJpaTest`?**
> A slice test annotation that starts only the JPA layer — your entity classes, repositories, and an in-memory DB. No web layer, no service layer, no Kafka. Used to test repository queries, custom `@Query` methods, and entity relationships. Fast (~3s) compared to `@SpringBootTest` (~15s).

---

## 12. Tricky Interview Questions

**Q: Consumer-driven vs Provider-driven contracts — which is better and why?**
> Consumer-driven is better in almost all cases. The consumer knows exactly which fields it uses — the provider doesn't know who's consuming what. Provider-driven contracts tend to over-specify (providers document everything they return), and consumers end up coupled to fields they don't actually use. When the provider changes a field the consumer doesn't use, a provider-driven contract fails needlessly. Consumer-driven: the consumer only specifies what it needs, and the provider verifies it can satisfy every consumer. This catches real breaking changes while ignoring irrelevant changes.

**Q: `@MockBean` vs `@Mock` — what's the difference?**
> `@Mock` (Mockito) creates a mock that's NOT registered in the Spring context. It's for pure unit tests where you instantiate the class under test manually. `@MockBean` replaces a bean IN the Spring application context with a Mockito mock. It's for `@SpringBootTest` — the context starts but a specific bean (like `UserServiceClient`) is replaced with a mock you can control. `@MockBean` is slower (Spring context must start) but tests the full Spring integration.

**Q: How do you test a circuit breaker?**
> You need to trigger the CB to open. In tests: configure `minimumNumberOfCalls=5, failureRateThreshold=50`. Mock the dependency to throw exceptions. Make 10+ calls — fills the sliding window with 100% failures. Then make one more call and assert: (a) the response is the fallback response, (b) the mock was NOT called (CB is open — no attempt made). Verify CB state via `CircuitBreakerRegistry` or `GET /actuator/circuitbreakers`.

---

## 13. Scenario-Based Questions

**Scenario 1:** "You're onboarding a new team to consumer contract testing. They're not familiar with Pact. What's your minimum viable contract testing setup?"

> Start with Spring Cloud Contract instead of Pact — it's already in the Spring ecosystem. Step 1: have the consumer team write their Feign client tests using the provider's WireMock stubs (generated from the contract). Step 2: have the provider team run the auto-generated contract tests in their CI. Step 3: fail the provider's pipeline if any generated contract test fails. This is the 20% effort that gives 80% of the benefit.

**Scenario 2:** "A service has 200 tests. They take 8 minutes to run. How do you speed this up?"

> 1. Identify test types — are they all `@SpringBootTest`? If yes, convert business logic tests to pure unit tests (no Spring context = 100x faster). 2. Reuse the Spring context: Spring caches the context across tests unless `@DirtiesContext` or different configurations force a reload. 3. Run test suites in parallel (`maven-surefire-plugin` with `<forkCount>` or JUnit Platform parallel execution). 4. Use `@DataJpaTest` instead of `@SpringBootTest` for repository tests. Target: unit tests < 30s, integration tests < 3 min.

---

## 14. Quick Revision Cheat Sheet

```
TESTING PYRAMID
  Unit tests     → 70% — no Spring context, pure Mockito, instant
  Integration    → 20% — @SpringBootTest + @MockBean + real DB (H2)
  Contract       → 5%  — Pact / Spring Cloud Contract
  E2E            → 5%  — full system, RestAssured, Docker Compose

SLICE TEST ANNOTATIONS
  @WebMvcTest       → web layer only (controllers, filters)
  @DataJpaTest      → JPA layer only (repositories, entities)
  @SpringBootTest   → full context (everything)

MOCKING STRATEGIES
  @Mock             → Mockito mock, NOT in Spring context (unit test)
  @MockBean         → replaces Spring bean with Mockito mock (integration test)
  @SpyBean          → wraps real bean, can verify calls (partial mock)
  WireMock          → HTTP stub server (more realistic than Mockito for Feign)

KAFKA TESTING
  @EmbeddedKafka    → in-memory Kafka, fast, no Docker
  KafkaContainer    → real Kafka via Testcontainers, production-identical
  Awaitility        → async assertion (never Thread.sleep)

CONTRACT TESTING
  Consumer-driven   → consumer defines what it needs, provider verifies
  Pact              → language-agnostic, has Pact Broker
  Spring Cloud Contract → Spring-native, generates WireMock stubs + provider tests
  Key benefit       → catches API drift WITHOUT running both services

TESTCONTAINERS
  @Testcontainers   → JUnit 5 extension manages container lifecycle
  @Container        → field annotation (static = shared, non-static = per test)
  @DynamicPropertySource → injects container ports into Spring context

WHAT INTERVIEWERS WANT TO HEAR
  "I mock Feign clients with @MockBean — never start real dependent services"
  "I use contract testing to catch API drift between services"
  "I use Awaitility for async Kafka tests, never Thread.sleep()"
  "I use @DataJpaTest for repo tests — no need for full @SpringBootTest"
  "The testing pyramid — more unit tests, fewer E2E tests"
```
