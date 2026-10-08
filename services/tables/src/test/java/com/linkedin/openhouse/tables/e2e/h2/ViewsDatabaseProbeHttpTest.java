package com.linkedin.openhouse.tables.e2e.h2;

import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkedin.openhouse.common.audit.AuditHandler;
import com.linkedin.openhouse.common.security.DummyTokenInterceptor;
import com.linkedin.openhouse.common.test.cluster.PropertyOverrideContextInitializer;
import com.linkedin.openhouse.housetables.client.api.UserTableApi;
import com.linkedin.openhouse.housetables.client.invoker.ApiClient;
import com.linkedin.openhouse.internal.catalog.OpenHouseInternalCatalog;
import com.linkedin.openhouse.internal.catalog.mapper.HouseTableMapper;
import com.linkedin.openhouse.internal.catalog.repository.HouseTableRepository;
import com.linkedin.openhouse.internal.catalog.repository.HouseTableRepositoryImpl;
import com.linkedin.openhouse.internal.catalog.repository.HtsRetryUtils;
import com.linkedin.openhouse.tables.audit.model.OperationStatus;
import com.linkedin.openhouse.tables.audit.model.ViewAuditEvent;
import com.linkedin.openhouse.tables.controller.DatabasesController;
import com.linkedin.openhouse.tables.exception.ViewExceptionHandler;
import com.linkedin.openhouse.tables.mock.properties.AuthorizationPropertiesInitializer;
import com.linkedin.openhouse.tables.model.ViewModelConstants;
import com.linkedin.openhouse.tables.repository.OpenHouseInternalViewRepository;
import com.linkedin.openhouse.tables.services.ViewAdmissionService;
import com.linkedin.openhouse.tables.services.ViewsFeatureGate;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.QueueDispatcher;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.method.HandlerMethod;

/**
 * The view service's database-existence probe over the real enumeration chain: real generated HTS
 * client and HTTP adapter, real catalog, table repository and DatabasesService, real view service,
 * API and advice. Only the HTS server is a stand-in, answering with actual HTTP statuses.
 *
 * <p>Each test installs a FRESH HTTP adapter into the real catalog, so its retry template is first
 * initialized by the enumeration itself; the exact request counts below describe that setup only.
 * In production a template first initialized by a write can allow a single attempt, so the contract
 * asserted everywhere is the status, audit and absence of mutation, not the count alone.
 */
@SpringBootTest(classes = {SpringH2Application.class, ViewsDatabaseProbeHttpTest.Config.class})
@AutoConfigureMockMvc
@ContextConfiguration(
    initializers = {
      PropertyOverrideContextInitializer.class,
      AuthorizationPropertiesInitializer.class
    })
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public class ViewsDatabaseProbeHttpTest {

  private static final String VIEWS_PATH =
      "/v1/databases/" + ViewModelConstants.DATABASE_ID + "/views";
  private static final String VIEW_PATH = VIEWS_PATH + "/" + ViewModelConstants.VIEW_ID;
  private static final String HTS_OUTAGE_BODY = "hts-outage-detail-marker";

  private static MockWebServer htsServer;

  @Autowired private MockMvc mvc;
  @Autowired private OpenHouseInternalCatalog catalog;
  @Autowired private HouseTableMapper houseTableMapper;

  @MockBean private ViewsFeatureGate viewsFeatureGate;
  @MockBean private AuditHandler<ViewAuditEvent> viewAuditHandler;
  // Mocked only to prove that nothing downstream of the probe is ever reached.
  @MockBean private OpenHouseInternalViewRepository viewRepository;
  @MockBean private ViewAdmissionService admissionService;

  private HouseTableRepository originalRepository;
  private HouseTableRepositoryImpl httpRepository;
  private String jwtAccessToken;

  @BeforeAll
  static void startHts() throws IOException {
    htsServer = new MockWebServer();
    htsServer.start();
  }

  @AfterAll
  static void stopHts() throws IOException {
    htsServer.shutdown();
  }

  @BeforeEach
  void installFreshHttpAdapter() throws Exception {
    jwtAccessToken =
        new DummyTokenInterceptor.DummySecurityJWT("DUMMY_ANONYMOUS_USER").buildNoopJWT();
    Mockito.when(viewsFeatureGate.isEnabled(ViewModelConstants.DATABASE_ID)).thenReturn(true);

    ApiClient apiClient = new ApiClient();
    apiClient.setBasePath(String.format("http://localhost:%s", htsServer.getPort()));
    httpRepository = new HouseTableRepositoryImpl();
    ReflectionTestUtils.setField(httpRepository, "apiInstance", new UserTableApi(apiClient));
    ReflectionTestUtils.setField(httpRepository, "houseTableMapper", houseTableMapper);

    Object target = AopTestUtils.getUltimateTargetObject(catalog);
    originalRepository =
        (HouseTableRepository) ReflectionTestUtils.getField(target, "houseTableRepository");
    ReflectionTestUtils.setField(target, "houseTableRepository", httpRepository);
    Assertions.assertNull(
        ReflectionTestUtils.getField(httpRepository, "retryTemplate"),
        "no setup call may initialize the adapter's retry template before the enumeration");
  }

  @AfterEach
  void restoreRepositoryAndDrainHts() throws InterruptedException {
    Object target = AopTestUtils.getUltimateTargetObject(catalog);
    ReflectionTestUtils.setField(target, "houseTableRepository", originalRepository);
    htsServer.setDispatcher(new QueueDispatcher());
    while (htsServer.takeRequest(1, TimeUnit.MILLISECONDS) != null) {
      // Discard requests a failing assertion left unread.
    }
  }

  @Test
  public void getViewEnumerationOutageIsSanitized503WithoutOperationAudit() throws Exception {
    assertReadOutage(MockMvcRequestBuilders.get(VIEW_PATH));
  }

  @Test
  public void listViewsEnumerationOutageIsSanitized503WithoutOperationAudit() throws Exception {
    assertReadOutage(MockMvcRequestBuilders.get(VIEWS_PATH));
  }

  @Test
  public void createEnumerationOutageIsOneFailedAuditAndNoMutation() throws Exception {
    assertWriteOutage(
        MockMvcRequestBuilders.post(VIEWS_PATH)
            .contentType(MediaType.APPLICATION_JSON)
            .content(ViewModelConstants.createRequestWithoutBaseVersion().toJson()));
  }

  @Test
  public void replaceEnumerationOutageIsOneFailedAuditAndNoMutation() throws Exception {
    assertWriteOutage(
        MockMvcRequestBuilders.put(VIEW_PATH)
            .contentType(MediaType.APPLICATION_JSON)
            .content(ViewModelConstants.fullyPopulatedRequest().toJson()));
  }

  @Test
  public void deleteEnumerationOutageIsOneFailedAuditAndNoMutation() throws Exception {
    assertWriteOutage(MockMvcRequestBuilders.delete(VIEW_PATH));
  }

  /** A 404 from enumeration is an unexpected server fault, never the view's NO_SUCH_VIEW. */
  @Test
  public void enumerationNotFoundIsAnInternalErrorNotNoSuchView() throws Exception {
    htsServer.enqueue(htsResponse(404));

    MvcResult result = perform(MockMvcRequestBuilders.get(VIEW_PATH));

    Assertions.assertEquals(500, result.getResponse().getStatus());
    assertSanitized(result);
    Assertions.assertEquals(1, enumerationRequests(), "a 404 is not retried");
    Mockito.verify(viewAuditHandler, Mockito.never()).audit(any(ViewAuditEvent.class));
    Mockito.verifyNoInteractions(viewRepository, admissionService);
  }

  /** A write's enumeration 404 stays a known FAILED 500 before any mutation. */
  @Test
  public void writeEnumerationNotFoundIsOneFailedInternalErrorAndNoMutation() throws Exception {
    htsServer.enqueue(htsResponse(404));

    MvcResult result = perform(MockMvcRequestBuilders.delete(VIEW_PATH));

    Assertions.assertEquals(500, result.getResponse().getStatus());
    assertSanitized(result);
    Assertions.assertEquals(1, enumerationRequests(), "a 404 is not retried");
    assertOneFailedOperationAudit();
    Mockito.verifyNoInteractions(viewRepository, admissionService);
  }

  /**
   * The same enumeration serves the non-view database listing. Its status and error-body shape are
   * pinned to the baseline observed before the view fix; the message is not, because the exception
   * type intentionally changes underneath it.
   */
  @Test
  public void nonViewDatabaseEnumerationKeepsBaselineStatusAndBodyClass() throws Exception {
    for (int i = 0; i < HtsRetryUtils.MAX_RETRY_ATTEMPT; i++) {
      htsServer.enqueue(htsResponse(503));
    }

    // The table/database advice renders its own body shape, so no view sanitization is assumed.
    MvcResult result =
        mvc.perform(
                MockMvcRequestBuilders.get("/v1/databases")
                    .accept(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + jwtAccessToken))
            .andReturn();

    HandlerMethod handler = (HandlerMethod) result.getHandler();
    Assertions.assertEquals(DatabasesController.class, handler.getBeanType());
    Assertions.assertEquals("getAllDatabases", handler.getMethod().getName());
    String body = result.getResponse().getContentAsString();
    int requests = enumerationRequests();
    System.out.println(
        "OBSERVED_NON_VIEW_ENUMERATION status="
            + result.getResponse().getStatus()
            + " requests="
            + requests
            + " body="
            + body);
    // One attempt for an untyped failure; up to the retry limit once it is typed.
    Assertions.assertTrue(
        requests >= 1 && requests <= HtsRetryUtils.MAX_RETRY_ATTEMPT, "requests=" + requests);
    // Observed at 63331b2e with production unchanged: 500, one request, and the generic
    // ErrorResponseBody {status, error, message, stacktrace, cause}.
    Assertions.assertEquals(500, result.getResponse().getStatus());
    JsonNode json = new ObjectMapper().readTree(body);
    List<String> fields = new ArrayList<>();
    json.fieldNames().forEachRemaining(fields::add);
    Assertions.assertEquals(
        Arrays.asList("status", "error", "message", "stacktrace", "cause"), fields, body);
    Assertions.assertEquals("INTERNAL_SERVER_ERROR", json.get("status").asText(), body);
    Assertions.assertEquals("Internal Server Error", json.get("error").asText(), body);
    Mockito.verify(viewAuditHandler, Mockito.never()).audit(any(ViewAuditEvent.class));
  }

  private void assertReadOutage(MockHttpServletRequestBuilder request) throws Exception {
    enqueueTerminalOutage();

    MvcResult result = perform(request);

    Assertions.assertEquals(503, result.getResponse().getStatus());
    assertSanitized(result);
    Assertions.assertEquals(HtsRetryUtils.MAX_RETRY_ATTEMPT, enumerationRequests());
    Mockito.verify(viewAuditHandler, Mockito.never()).audit(any(ViewAuditEvent.class));
    Mockito.verifyNoInteractions(viewRepository, admissionService);
  }

  private void assertWriteOutage(MockHttpServletRequestBuilder request) throws Exception {
    enqueueTerminalOutage();

    MvcResult result = perform(request);

    Assertions.assertEquals(503, result.getResponse().getStatus());
    assertSanitized(result);
    Assertions.assertEquals(HtsRetryUtils.MAX_RETRY_ATTEMPT, enumerationRequests());
    assertOneFailedOperationAudit();
    // The probe precedes capture, authorization, admission, allocation and every mutation.
    Mockito.verifyNoInteractions(viewRepository, admissionService);
  }

  /** Every retryable 5xx, then the terminal one. */
  private static void enqueueTerminalOutage() {
    for (int code : Arrays.asList(500, 502, 503)) {
      htsServer.enqueue(htsResponse(code));
    }
  }

  private static MockResponse htsResponse(int code) {
    return new MockResponse()
        .setResponseCode(code)
        .setBody("{\"message\":\"" + HTS_OUTAGE_BODY + "\"}")
        .addHeader("Content-Type", "application/json");
  }

  private MvcResult perform(MockHttpServletRequestBuilder request) throws Exception {
    return mvc.perform(
            request
                .accept(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + jwtAccessToken))
        .andExpect(jsonPath("$.cause").doesNotExist())
        .andExpect(jsonPath("$.stacktrace").doesNotExist())
        .andReturn();
  }

  private static void assertSanitized(MvcResult result) throws Exception {
    String body = result.getResponse().getContentAsString();
    Assertions.assertFalse(body.contains(HTS_OUTAGE_BODY), body);
  }

  /** Actual wire requests, every one of which must be the neutral enumeration GET. */
  private static int enumerationRequests() throws InterruptedException {
    List<RecordedRequest> requests = new ArrayList<>();
    RecordedRequest request;
    while ((request = htsServer.takeRequest(1, TimeUnit.SECONDS)) != null) {
      requests.add(request);
    }
    for (RecordedRequest recorded : requests) {
      Assertions.assertEquals("GET", recorded.getMethod(), recorded.getPath());
      Assertions.assertTrue(
          recorded.getPath().startsWith("/hts/tables/query"), "not enumeration: " + recorded);
    }
    return requests.size();
  }

  private void assertOneFailedOperationAudit() {
    ArgumentCaptor<ViewAuditEvent> event = ArgumentCaptor.forClass(ViewAuditEvent.class);
    Mockito.verify(viewAuditHandler, Mockito.times(1)).audit(event.capture());
    ViewAuditEvent audited = event.getValue();
    Assertions.assertEquals(OperationStatus.FAILED, audited.getOperationStatus());
    Assertions.assertEquals(ViewModelConstants.DATABASE_ID, audited.getDatabaseName());
    Assertions.assertEquals(ViewModelConstants.VIEW_ID, audited.getViewName());
    Assertions.assertNull(audited.getViewUUID(), "no capture was reached");
    Assertions.assertNull(audited.getOldMetadataLocation());
    Assertions.assertNull(audited.getNewMetadataLocation());
  }

  /** The view advice the production application scans, which this H2 application does not. */
  @TestConfiguration
  static class Config {
    @Bean
    ViewExceptionHandler viewExceptionHandler() {
      return new ViewExceptionHandler();
    }
  }
}
