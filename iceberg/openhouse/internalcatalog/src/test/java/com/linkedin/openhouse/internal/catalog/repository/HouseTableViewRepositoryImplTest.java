package com.linkedin.openhouse.internal.catalog.repository;

import static com.linkedin.openhouse.internal.catalog.HouseTableModelConstants.HOUSE_TABLE;
import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.linkedin.openhouse.housetables.client.api.ToggleStatusApi;
import com.linkedin.openhouse.housetables.client.api.UserTableApi;
import com.linkedin.openhouse.housetables.client.invoker.ApiClient;
import com.linkedin.openhouse.housetables.client.model.EntityResponseBodyUserTable;
import com.linkedin.openhouse.housetables.client.model.GetAllEntityResponseBodyUserTable;
import com.linkedin.openhouse.housetables.client.model.PageUserTable;
import com.linkedin.openhouse.housetables.client.model.UserTable;
import com.linkedin.openhouse.internal.catalog.mapper.HouseTableMapper;
import com.linkedin.openhouse.internal.catalog.model.HouseTable;
import com.linkedin.openhouse.internal.catalog.model.HouseTablePrimaryKey;
import com.linkedin.openhouse.internal.catalog.repository.exception.HouseTableCallerException;
import com.linkedin.openhouse.internal.catalog.repository.exception.HouseTableConcurrentUpdateException;
import com.linkedin.openhouse.internal.catalog.repository.exception.HouseTableNotFoundException;
import com.linkedin.openhouse.internal.catalog.repository.exception.HouseTableRepositoryStateUnknownException;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.QueueDispatcher;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Bean;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.util.ReflectionUtils;
import reactor.core.publisher.Mono;

/**
 * Wire-level behavior of the typed view adapters against a mock House Table server.
 *
 * <p>The point of driving a real server rather than stubbing the generated client is that the whole
 * generated code path executes, so a wrong route, a wrong verb, or a body that leaks entity type
 * fails here instead of in production.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@SpringBootTest
public class HouseTableViewRepositoryImplTest {

  private static final String VIEW_DB = "viewdb";
  private static final String VIEW_ID = "v1";
  private static final String VIEW_METADATA_LOCATION = "/viewdb/v1-uuid/00001-abc.metadata.json";

  @Autowired
  @Qualifier("viewRepoTest")
  HouseTableRepository htsRepo;

  @Autowired HouseTableMapper houseTableMapper;

  @SpyBean UserTableApi userTableApi;

  @TestConfiguration
  public static class MockWebServerConfiguration {
    @Bean
    public UserTableApi provideMockHtsApiInstance() {
      return new UserTableApi(getMockServerApiClient());
    }

    @Bean
    public ToggleStatusApi provideMockHtsApiInstanceForToggle() {
      return new ToggleStatusApi(getMockServerApiClient());
    }

    private ApiClient getMockServerApiClient() {
      ApiClient apiClient = new ApiClient();
      apiClient.setBasePath(String.format("http://localhost:%s", mockHtsServer.getPort()));
      return apiClient;
    }

    @Bean
    @Qualifier("viewRepoTest")
    public HouseTableRepository provideRealHtsRepository() {
      return new HouseTableRepositoryImpl();
    }
  }

  private static MockWebServer mockHtsServer;

  @BeforeAll
  static void setUp() throws IOException {
    mockHtsServer = new MockWebServer();
    mockHtsServer.start();
  }

  @AfterAll
  static void tearDown() throws IOException {
    mockHtsServer.shutdown();
  }

  /**
   * The server outlives a single test method, so a test whose call never reaches the wire would
   * otherwise hand its queued response to the next test. Swapping in a fresh dispatcher empties the
   * response queue, and draining the recorded requests keeps request assertions honest.
   */
  @AfterEach
  void resetMockServerState() throws InterruptedException {
    mockHtsServer.setDispatcher(new QueueDispatcher());
    RecordedRequest leftover = mockHtsServer.takeRequest(1, TimeUnit.MILLISECONDS);
    while (leftover != null) {
      leftover = mockHtsServer.takeRequest(1, TimeUnit.MILLISECONDS);
    }
  }

  private static HouseTablePrimaryKey viewKey() {
    return HouseTablePrimaryKey.builder().databaseId(VIEW_DB).tableId(VIEW_ID).build();
  }

  private static HouseTable viewPointer() {
    return HouseTable.builder()
        .databaseId(VIEW_DB)
        .tableId(VIEW_ID)
        .tableLocation(VIEW_METADATA_LOCATION)
        .tableVersion("INITIAL_VERSION")
        .storageType("local")
        .build();
  }

  private UserTable viewUserTable(String entityType) {
    UserTable userTable = houseTableMapper.toUserTable(viewPointer());
    userTable.setEntityType(entityType);
    return userTable;
  }

  private void enqueueEntity(int code, UserTable entity) {
    EntityResponseBodyUserTable response = new EntityResponseBodyUserTable();
    response.entity(entity);
    mockHtsServer.enqueue(
        new MockResponse()
            .setResponseCode(code)
            .setBody(new Gson().toJson(response))
            .addHeader("Content-Type", "application/json"));
  }

  private void enqueueStatus(int code) {
    mockHtsServer.enqueue(
        new MockResponse()
            .setResponseCode(code)
            .setBody("")
            .addHeader("Content-Type", "application/json"));
  }

  private static RecordedRequest nextRequest() throws InterruptedException {
    RecordedRequest request = mockHtsServer.takeRequest(30, TimeUnit.SECONDS);
    Assertions.assertNotNull(request, "expected a request to reach House Table");
    return request;
  }

  /* -------------------------------------------------------------------------
   * Generated-client endpoint wiring.
   * ---------------------------------------------------------------------- */

  /** Neutral occupancy resolves through the type-agnostic entity route. */
  @Test
  public void findEntityByIdCallsTheNeutralEntityEndpoint() throws InterruptedException {
    enqueueEntity(200, viewUserTable("VIEW"));

    Optional<HouseTable> found = htsRepo.findEntityById(viewKey());

    RecordedRequest request = nextRequest();
    Assertions.assertEquals("GET", request.getMethod());
    assertThat(request.getPath()).startsWith("/hts/entities?");
    assertThat(request.getPath()).contains("databaseId=" + VIEW_DB);
    assertThat(request.getPath()).contains("tableId=" + VIEW_ID);
    Assertions.assertTrue(found.isPresent());
    Assertions.assertEquals("VIEW", found.get().getEntityType());
    Assertions.assertEquals(VIEW_METADATA_LOCATION, found.get().getTableLocation());
  }

  /**
   * House Table resolves a legacy row's absent column to TABLE before it ever reaches the wire, so
   * the neutral read only ever sees a canonical discriminator and must map it faithfully. TABLE is
   * pinned here and VIEW above, which is the whole vocabulary.
   */
  @Test
  public void findEntityByIdMapsTheCanonicalTableDiscriminator() throws InterruptedException {
    enqueueEntity(200, viewUserTable("TABLE"));

    Optional<HouseTable> found = htsRepo.findEntityById(viewKey());

    nextRequest();
    Assertions.assertTrue(found.isPresent());
    Assertions.assertEquals("TABLE", found.get().getEntityType());
    Assertions.assertEquals(VIEW_METADATA_LOCATION, found.get().getTableLocation());
  }

  @Test
  public void findViewByIdCallsTheTypedViewEndpoint() throws InterruptedException {
    enqueueEntity(200, viewUserTable("VIEW"));

    Optional<HouseTable> found = htsRepo.findViewById(viewKey());

    RecordedRequest request = nextRequest();
    Assertions.assertEquals("GET", request.getMethod());
    assertThat(request.getPath()).startsWith("/hts/views?");
    assertThat(request.getPath()).contains("databaseId=" + VIEW_DB);
    assertThat(request.getPath()).contains("tableId=" + VIEW_ID);
    Assertions.assertTrue(found.isPresent());
    Assertions.assertEquals(VIEW_METADATA_LOCATION, found.get().getTableLocation());
    Assertions.assertEquals("local", found.get().getStorageType());
  }

  /** House Table answers a typed lookup for a missing view with 404, which reads as absent. */
  @Test
  public void findViewByIdTreatsNotFoundAsAbsent() {
    enqueueStatus(404);

    Assertions.assertFalse(htsRepo.findViewById(viewKey()).isPresent());
  }

  @Test
  public void findAllViewsByDatabaseIdCallsThePaginatedViewQueryEndpoint()
      throws InterruptedException {
    List<UserTable> views = new ArrayList<>();
    views.add(viewUserTable("VIEW"));
    UserTable second = viewUserTable("VIEW");
    second.setTableId("v2");
    views.add(second);

    GetAllEntityResponseBodyUserTable pageResponse = new GetAllEntityResponseBodyUserTable();
    PageUserTable pageUserTable = new PageUserTable();
    pageUserTable.setContent(views);
    pageUserTable.setNumber(0);
    pageUserTable.setSize(2);
    pageUserTable.setTotalElements(5L);
    Field pageResults =
        ReflectionUtils.findField(GetAllEntityResponseBodyUserTable.class, "pageResults");
    Assertions.assertNotNull(pageResults);
    ReflectionUtils.makeAccessible(pageResults);
    ReflectionUtils.setField(pageResults, pageResponse, pageUserTable);

    mockHtsServer.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setBody(new Gson().toJson(pageResponse))
            .addHeader("Content-Type", "application/json"));

    Pageable pageable = PageRequest.of(0, 2, Sort.unsorted());
    Page<HouseTable> page = htsRepo.findAllViewsByDatabaseId(VIEW_DB, pageable);

    RecordedRequest request = nextRequest();
    Assertions.assertEquals("GET", request.getMethod());
    assertThat(request.getPath()).startsWith("/v1/hts/views/query?");
    assertThat(request.getPath()).contains("databaseId=" + VIEW_DB);
    assertThat(request.getPath()).contains("page=0");
    assertThat(request.getPath()).contains("size=2");

    Assertions.assertEquals(2, page.getContent().size());
    Assertions.assertEquals(5L, page.getTotalElements());
    Assertions.assertEquals(3, page.getTotalPages());
    Assertions.assertTrue(
        page.getContent().stream().allMatch(row -> "VIEW".equals(row.getEntityType())));
  }

  /**
   * The view write goes to the typed route and says what it is writing. Entity type is a required
   * field of the House Table contract now, so leaving it for the route to infer would put this
   * client out of contract; the route-side stamp survives only as a defensive fallback.
   */
  @Test
  public void saveViewPutsToTheTypedViewRouteDeclaringTheViewEntityType()
      throws InterruptedException {
    enqueueEntity(201, viewUserTable("VIEW"));

    HouseTable saved = htsRepo.saveView(viewPointer());

    RecordedRequest request = nextRequest();
    Assertions.assertEquals("PUT", request.getMethod());
    assertThat(request.getPath()).isEqualTo("/hts/views");

    JsonObject body = JsonParser.parseString(request.getBody().readUtf8()).getAsJsonObject();
    JsonObject entity = body.getAsJsonObject("entity");
    JsonElement declaredEntityType = entity.get("entityType");
    Assertions.assertTrue(
        declaredEntityType != null && !declaredEntityType.isJsonNull(),
        "outgoing view body must declare its entity type");
    Assertions.assertEquals(
        "VIEW",
        declaredEntityType.getAsString(),
        "the view route must be told, in the canonical spelling, that it is receiving a view");
    Assertions.assertEquals(VIEW_METADATA_LOCATION, entity.get("metadataLocation").getAsString());
    Assertions.assertEquals("INITIAL_VERSION", entity.get("tableVersion").getAsString());

    Assertions.assertEquals(VIEW_METADATA_LOCATION, saved.getTableLocation());
  }

  @Test
  public void deleteViewByIdCallsTheTypedViewDeleteEndpoint() throws InterruptedException {
    enqueueStatus(204);

    Assertions.assertTrue(htsRepo.deleteViewById(viewKey()));

    RecordedRequest request = nextRequest();
    Assertions.assertEquals("DELETE", request.getMethod());
    assertThat(request.getPath()).startsWith("/hts/views?");
    assertThat(request.getPath()).contains("databaseId=" + VIEW_DB);
    assertThat(request.getPath()).contains("tableId=" + VIEW_ID);
  }

  /** Views are hard deleted, so no soft-delete flag is ever negotiated on the wire. */
  @Test
  public void deleteViewByIdReportsFalseWhenTheViewIsNotThere() {
    enqueueStatus(404);

    Assertions.assertFalse(htsRepo.deleteViewById(viewKey()));
  }

  /* -------------------------------------------------------------------------
   * One write, no blind retry.
   * ---------------------------------------------------------------------- */

  /**
   * A 5xx leaves the outcome genuinely unknown. Retrying could double-apply, so the adapter sends
   * once and reports unknown state; the caller turns that into a commit-state-unknown result.
   */
  @Test
  public void saveViewSendsExactlyOneRequestPerAmbiguousServerError() throws InterruptedException {
    for (int code : Arrays.asList(500, 504)) {
      enqueueStatus(code);
      CustomRetryListener retryListener = new CustomRetryListener();
      ((HouseTableRepositoryImpl) htsRepo)
          .getHtsRetryTemplate(Collections.singletonList(IllegalStateException.class))
          .registerListener(retryListener);

      Assertions.assertThrows(
          HouseTableRepositoryStateUnknownException.class, () -> htsRepo.saveView(viewPointer()));

      RecordedRequest request = nextRequest();
      Assertions.assertEquals("PUT", request.getMethod());
      assertThat(request.getPath()).isEqualTo("/hts/views");
      Assertions.assertNull(
          mockHtsServer.takeRequest(1, TimeUnit.SECONDS),
          "a view write must never be retried, code " + code);
      Mockito.verify(userTableApi, Mockito.times(1)).putUserView(Mockito.any());
      Mockito.clearInvocations(userTableApi);
      Assertions.assertEquals(
          0, retryListener.getRetryCount(), "no retry may be recorded for a view write");
    }
  }

  /**
   * The request left this process before the connection died, so the server may well have applied
   * it. That is unknown state, not a failure that can be retried.
   */
  @Test
  public void saveViewReportsUnknownStateWhenTheConnectionDiesAfterTheRequestIsSent()
      throws InterruptedException {
    mockHtsServer.enqueue(
        new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST));

    CustomRetryListener retryListener = new CustomRetryListener();
    ((HouseTableRepositoryImpl) htsRepo)
        .getHtsRetryTemplate(Collections.singletonList(IllegalStateException.class))
        .registerListener(retryListener);

    Assertions.assertThrows(
        HouseTableRepositoryStateUnknownException.class, () -> htsRepo.saveView(viewPointer()));

    Mockito.verify(userTableApi, Mockito.times(1)).putUserView(Mockito.any());
    RecordedRequest sent = nextRequest();
    Assertions.assertEquals("PUT", sent.getMethod());
    assertThat(sent.getPath()).isEqualTo("/hts/views");
    Assertions.assertNull(
        mockHtsServer.takeRequest(1, TimeUnit.SECONDS),
        "the request already left this process; resending it could double-apply");
    Assertions.assertEquals(0, retryListener.getRetryCount());
  }

  /**
   * A block timeout is what {@code Mono.block(Duration)} throws when the write outlives its budget.
   * The request is already in flight, so this is unknown state too. The failure is injected on the
   * client rather than delayed on the server so the assertion costs nothing in wall-clock time.
   */
  @Test
  public void saveViewReportsUnknownStateOnABlockTimeout() {
    Mockito.doReturn(
            Mono.error(
                new IllegalStateException("Timeout on blocking read for 60000000000 NANOSECONDS")))
        .when(userTableApi)
        .putUserView(Mockito.any());

    CustomRetryListener retryListener = new CustomRetryListener();
    ((HouseTableRepositoryImpl) htsRepo)
        .getHtsRetryTemplate(Collections.singletonList(IllegalStateException.class))
        .registerListener(retryListener);

    Assertions.assertThrows(
        HouseTableRepositoryStateUnknownException.class, () -> htsRepo.saveView(viewPointer()));

    Mockito.verify(userTableApi, Mockito.times(1)).putUserView(Mockito.any());
    Assertions.assertEquals(0, retryListener.getRetryCount());
  }

  /** A losing compare-and-swap is reported as a conflict, and never retried into a second write. */
  @Test
  public void saveViewSurfacesConflictWithoutRetrying() throws InterruptedException {
    enqueueStatus(409);
    CustomRetryListener retryListener = new CustomRetryListener();
    ((HouseTableRepositoryImpl) htsRepo)
        .getHtsRetryTemplate(Collections.singletonList(IllegalStateException.class))
        .registerListener(retryListener);

    Assertions.assertThrowsExactly(
        HouseTableConcurrentUpdateException.class, () -> htsRepo.saveView(viewPointer()));

    nextRequest();
    Assertions.assertNull(mockHtsServer.takeRequest(1, TimeUnit.SECONDS));
    Assertions.assertEquals(0, retryListener.getRetryCount());
  }

  /** Caller-side failures keep their classification so the later layer can preserve the status. */
  @Test
  public void saveViewPreservesCallerFailuresWithoutRetrying() throws InterruptedException {
    for (int code : Arrays.asList(400, 401, 403, 429)) {
      enqueueStatus(code);
      CustomRetryListener retryListener = new CustomRetryListener();
      ((HouseTableRepositoryImpl) htsRepo)
          .getHtsRetryTemplate(Collections.singletonList(IllegalStateException.class))
          .registerListener(retryListener);

      Assertions.assertThrowsExactly(
          HouseTableCallerException.class,
          () -> htsRepo.saveView(viewPointer()),
          String.valueOf(code));

      Mockito.verify(userTableApi, Mockito.times(1)).putUserView(Mockito.any());
      Mockito.clearInvocations(userTableApi);
      nextRequest();
      Assertions.assertNull(
          mockHtsServer.takeRequest(1, TimeUnit.SECONDS),
          "a caller failure must not be retried, code " + code);
      Assertions.assertEquals(0, retryListener.getRetryCount(), "code " + code);
    }

    enqueueStatus(404);
    Assertions.assertThrowsExactly(
        HouseTableNotFoundException.class, () -> htsRepo.saveView(viewPointer()));
    Mockito.verify(userTableApi, Mockito.times(1)).putUserView(Mockito.any());
  }

  /**
   * An empty completion is a success signal that carries nothing. It never reaches the error
   * handler, so without an explicit guard the adapter would return null and the caller would fail
   * later with an NPE for a mutation House Table may well have applied.
   */
  @Test
  public void saveViewReportsUnknownStateWhenThePublisherCompletesEmpty() {
    Mockito.doReturn(Mono.empty()).when(userTableApi).putUserView(Mockito.any());

    CustomRetryListener retryListener = new CustomRetryListener();
    ((HouseTableRepositoryImpl) htsRepo)
        .getHtsRetryTemplate(Collections.singletonList(IllegalStateException.class))
        .registerListener(retryListener);

    Assertions.assertThrows(
        HouseTableRepositoryStateUnknownException.class, () -> htsRepo.saveView(viewPointer()));

    Mockito.verify(userTableApi, Mockito.times(1)).putUserView(Mockito.any());
    Assertions.assertEquals(0, retryListener.getRetryCount());
  }

  /** A 200 whose body carries no entity is the same ambiguity arriving over the wire. */
  @Test
  public void saveViewReportsUnknownStateWhenTheResponseCarriesNoEntity()
      throws InterruptedException {
    enqueueEntity(200, null);

    Assertions.assertThrows(
        HouseTableRepositoryStateUnknownException.class, () -> htsRepo.saveView(viewPointer()));

    RecordedRequest request = nextRequest();
    Assertions.assertEquals("PUT", request.getMethod());
    Assertions.assertNull(
        mockHtsServer.takeRequest(1, TimeUnit.SECONDS), "an ambiguous write is never resent");
  }

  /* -------------------------------------------------------------------------
   * The paginated list is a read, so it keeps the shared bounded retry.
   * ---------------------------------------------------------------------- */

  private void enqueueViewPage(int totalElements) {
    List<UserTable> views = new ArrayList<>();
    views.add(viewUserTable("VIEW"));
    GetAllEntityResponseBodyUserTable pageResponse = new GetAllEntityResponseBodyUserTable();
    PageUserTable pageUserTable = new PageUserTable();
    pageUserTable.setContent(views);
    pageUserTable.setNumber(0);
    pageUserTable.setSize(1);
    pageUserTable.setTotalElements((long) totalElements);
    Field pageResults =
        ReflectionUtils.findField(GetAllEntityResponseBodyUserTable.class, "pageResults");
    Assertions.assertNotNull(pageResults);
    ReflectionUtils.makeAccessible(pageResults);
    ReflectionUtils.setField(pageResults, pageResponse, pageUserTable);
    mockHtsServer.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setBody(new Gson().toJson(pageResponse))
            .addHeader("Content-Type", "application/json"));
  }

  /**
   * A transient server error on a read is safe to repeat, so the list must actually engage the
   * bounded retry rather than surfacing the raw web-client failure on the first attempt.
   */
  @Test
  public void findAllViewsByDatabaseIdRetriesATransientServerErrorAndThenSucceeds()
      throws InterruptedException {
    enqueueStatus(503);
    enqueueViewPage(1);

    CustomRetryListener retryListener = new CustomRetryListener();
    ((HouseTableRepositoryImpl) htsRepo)
        .getHtsRetryTemplate(
            Arrays.asList(
                HouseTableRepositoryStateUnknownException.class, IllegalStateException.class))
        .registerListener(retryListener);

    Page<HouseTable> page =
        htsRepo.findAllViewsByDatabaseId(VIEW_DB, PageRequest.of(0, 1, Sort.unsorted()));

    Assertions.assertEquals(1, page.getContent().size());
    Assertions.assertEquals(1, retryListener.getRetryCount());
    assertThat(nextRequest().getPath()).startsWith("/v1/hts/views/query?");
    assertThat(nextRequest().getPath()).startsWith("/v1/hts/views/query?");
    Assertions.assertNull(mockHtsServer.takeRequest(1, TimeUnit.SECONDS));
  }

  /** The retry is bounded: once the attempts are used up the classified failure is reported. */
  @Test
  public void findAllViewsByDatabaseIdStopsAfterTheConfiguredNumberOfAttempts()
      throws InterruptedException {
    for (int i = 0; i < HtsRetryUtils.MAX_RETRY_ATTEMPT; i++) {
      enqueueStatus(500);
    }

    CustomRetryListener retryListener = new CustomRetryListener();
    ((HouseTableRepositoryImpl) htsRepo)
        .getHtsRetryTemplate(
            Arrays.asList(
                HouseTableRepositoryStateUnknownException.class, IllegalStateException.class))
        .registerListener(retryListener);

    Assertions.assertThrows(
        HouseTableRepositoryStateUnknownException.class,
        () -> htsRepo.findAllViewsByDatabaseId(VIEW_DB, PageRequest.of(0, 1, Sort.unsorted())));

    Assertions.assertEquals(HtsRetryUtils.MAX_RETRY_ATTEMPT, retryListener.getRetryCount());
    for (int i = 0; i < HtsRetryUtils.MAX_RETRY_ATTEMPT; i++) {
      Assertions.assertEquals("GET", nextRequest().getMethod());
    }
    Assertions.assertNull(
        mockHtsServer.takeRequest(1, TimeUnit.SECONDS), "the bound must stop further attempts");
  }

  /* -------------------------------------------------------------------------
   * The typed delete is a mutation too, and gets the same one-shot treatment.
   * ---------------------------------------------------------------------- */

  @Test
  public void deleteViewByIdSendsExactlyOneRequestPerAmbiguousServerError()
      throws InterruptedException {
    for (int code : Arrays.asList(500, 504)) {
      enqueueStatus(code);
      CustomRetryListener retryListener = new CustomRetryListener();
      ((HouseTableRepositoryImpl) htsRepo)
          .getHtsRetryTemplate(Collections.singletonList(IllegalStateException.class))
          .registerListener(retryListener);

      Assertions.assertThrows(
          HouseTableRepositoryStateUnknownException.class,
          () -> htsRepo.deleteViewById(viewKey()),
          String.valueOf(code));

      RecordedRequest request = nextRequest();
      Assertions.assertEquals("DELETE", request.getMethod());
      assertThat(request.getPath()).startsWith("/hts/views?");
      Assertions.assertNull(
          mockHtsServer.takeRequest(1, TimeUnit.SECONDS),
          "a view delete must never be retried, code " + code);
      Mockito.verify(userTableApi, Mockito.times(1)).deleteView(Mockito.any(), Mockito.any());
      Mockito.clearInvocations(userTableApi);
      Assertions.assertEquals(0, retryListener.getRetryCount(), "code " + code);
    }
  }

  @Test
  public void deleteViewByIdReportsUnknownStateWhenTheConnectionDiesAfterTheRequestIsSent()
      throws InterruptedException {
    mockHtsServer.enqueue(
        new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST));

    CustomRetryListener retryListener = new CustomRetryListener();
    ((HouseTableRepositoryImpl) htsRepo)
        .getHtsRetryTemplate(Collections.singletonList(IllegalStateException.class))
        .registerListener(retryListener);

    Assertions.assertThrows(
        HouseTableRepositoryStateUnknownException.class, () -> htsRepo.deleteViewById(viewKey()));

    Mockito.verify(userTableApi, Mockito.times(1)).deleteView(Mockito.any(), Mockito.any());
    nextRequest();
    Assertions.assertNull(mockHtsServer.takeRequest(1, TimeUnit.SECONDS));
    Assertions.assertEquals(0, retryListener.getRetryCount());
  }

  @Test
  public void deleteViewByIdReportsUnknownStateOnABlockTimeout() {
    Mockito.doReturn(
            Mono.error(
                new IllegalStateException("Timeout on blocking read for 60000000000 NANOSECONDS")))
        .when(userTableApi)
        .deleteView(Mockito.any(), Mockito.any());

    CustomRetryListener retryListener = new CustomRetryListener();
    ((HouseTableRepositoryImpl) htsRepo)
        .getHtsRetryTemplate(Collections.singletonList(IllegalStateException.class))
        .registerListener(retryListener);

    Assertions.assertThrows(
        HouseTableRepositoryStateUnknownException.class, () -> htsRepo.deleteViewById(viewKey()));

    Mockito.verify(userTableApi, Mockito.times(1)).deleteView(Mockito.any(), Mockito.any());
    Assertions.assertEquals(0, retryListener.getRetryCount());
  }

  /**
   * A classified caller failure on the delete route keeps its classification and is sent once, for
   * the same reason as the write: the later layer has to be able to preserve the status, and a
   * retry of a mutation is never safe here.
   */
  @Test
  public void deleteViewByIdPreservesCallerFailuresWithoutRetrying() throws InterruptedException {
    for (int code : Arrays.asList(400, 401, 403, 429)) {
      enqueueStatus(code);
      CustomRetryListener retryListener = new CustomRetryListener();
      ((HouseTableRepositoryImpl) htsRepo)
          .getHtsRetryTemplate(Collections.singletonList(IllegalStateException.class))
          .registerListener(retryListener);

      Assertions.assertThrowsExactly(
          HouseTableCallerException.class,
          () -> htsRepo.deleteViewById(viewKey()),
          String.valueOf(code));

      Mockito.verify(userTableApi, Mockito.times(1)).deleteView(Mockito.any(), Mockito.any());
      Mockito.clearInvocations(userTableApi);
      RecordedRequest request = nextRequest();
      Assertions.assertEquals("DELETE", request.getMethod());
      assertThat(request.getPath()).startsWith("/hts/views?");
      Assertions.assertNull(
          mockHtsServer.takeRequest(1, TimeUnit.SECONDS),
          "a caller failure must not be retried, code " + code);
      Assertions.assertEquals(0, retryListener.getRetryCount(), "code " + code);
    }
  }

  /** A typed delete never negotiates the soft-delete flag the table route carries. */
  @Test
  public void deleteViewByIdNeverTouchesTheTableDeleteRoutes() throws InterruptedException {
    enqueueStatus(204);

    Assertions.assertTrue(htsRepo.deleteViewById(viewKey()));

    RecordedRequest request = nextRequest();
    assertThat(request.getPath()).startsWith("/hts/views?");
    assertThat(request.getPath()).doesNotContain("isSoftDelete");
    Mockito.verify(userTableApi, Mockito.never())
        .deleteTable(Mockito.any(), Mockito.any(), Mockito.any());
    Mockito.verify(userTableApi, Mockito.never()).deleteTable1(Mockito.any(), Mockito.any());
  }

  /* -------------------------------------------------------------------------
   * Reads keep the existing bounded retry.
   * ---------------------------------------------------------------------- */

  /** Reads are safe to repeat, so the typed view read keeps the shared bounded retry. */
  @Test
  public void findViewByIdRetriesTransientServerErrorsUpToTheConfiguredBound() {
    enqueueStatus(504);
    enqueueStatus(500);
    enqueueStatus(409);

    CustomRetryListener retryListener = new CustomRetryListener();
    ((HouseTableRepositoryImpl) htsRepo)
        .getHtsRetryTemplate(
            Arrays.asList(
                HouseTableRepositoryStateUnknownException.class, IllegalStateException.class))
        .registerListener(retryListener);

    Assertions.assertThrows(
        HouseTableConcurrentUpdateException.class, () -> htsRepo.findViewById(viewKey()));
    Assertions.assertEquals(HtsRetryUtils.MAX_RETRY_ATTEMPT, retryListener.getRetryCount());
  }

  /** The neutral occupancy read is a read too, and gets the same bounded retry. */
  @Test
  public void findEntityByIdRetriesTransientServerErrors() {
    enqueueStatus(503);
    enqueueEntity(200, viewUserTable("TABLE"));

    CustomRetryListener retryListener = new CustomRetryListener();
    ((HouseTableRepositoryImpl) htsRepo)
        .getHtsRetryTemplate(
            Arrays.asList(
                HouseTableRepositoryStateUnknownException.class, IllegalStateException.class))
        .registerListener(retryListener);

    Optional<HouseTable> found = htsRepo.findEntityById(viewKey());

    Assertions.assertTrue(found.isPresent());
    Assertions.assertEquals("TABLE", found.get().getEntityType());
    Assertions.assertEquals(1, retryListener.getRetryCount());
  }

  /** The typed view read never resolves a table pointer, so a view drop cannot hit a table. */
  @Test
  public void tablePointersRemainReachableOnlyThroughTheTableRoutes() throws InterruptedException {
    enqueueEntity(200, houseTableMapper.toUserTable(HOUSE_TABLE));

    HouseTable table =
        htsRepo
            .findById(
                HouseTablePrimaryKey.builder()
                    .databaseId(HOUSE_TABLE.getDatabaseId())
                    .tableId(HOUSE_TABLE.getTableId())
                    .build())
            .get();

    RecordedRequest request = nextRequest();
    assertThat(request.getPath()).startsWith("/hts/tables?");
    Assertions.assertEquals(HOUSE_TABLE.getTableLocation(), table.getTableLocation());
  }
}
