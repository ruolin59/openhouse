package com.linkedin.openhouse.tables.e2e.h2;

import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import com.linkedin.openhouse.cluster.storage.StorageManager;
import com.linkedin.openhouse.common.api.validator.ValidatorConstants;
import com.linkedin.openhouse.common.audit.AuditHandler;
import com.linkedin.openhouse.common.audit.model.ServiceAuditEvent;
import com.linkedin.openhouse.common.security.DummyTokenInterceptor;
import com.linkedin.openhouse.common.test.cluster.PropertyOverrideContextInitializer;
import com.linkedin.openhouse.internal.catalog.model.HouseTable;
import com.linkedin.openhouse.internal.catalog.model.HouseTablePrimaryKey;
import com.linkedin.openhouse.internal.catalog.repository.HouseTableRepository;
import com.linkedin.openhouse.internal.catalog.view.ViewMetadataCodec;
import com.linkedin.openhouse.tables.api.spec.v0.request.CreateUpdateViewRequestBody;
import com.linkedin.openhouse.tables.api.spec.v0.request.components.ViewRepresentation;
import com.linkedin.openhouse.tables.audit.model.OperationStatus;
import com.linkedin.openhouse.tables.audit.model.ViewAuditEvent;
import com.linkedin.openhouse.tables.exception.ViewExceptionHandler;
import com.linkedin.openhouse.tables.mock.audit.AuditEventInspection;
import com.linkedin.openhouse.tables.mock.logging.Log4j2LogCapture;
import com.linkedin.openhouse.tables.mock.properties.AuthorizationPropertiesInitializer;
import com.linkedin.openhouse.tables.model.TableDto;
import com.linkedin.openhouse.tables.model.TableDtoPrimaryKey;
import com.linkedin.openhouse.tables.model.TableModelConstants;
import com.linkedin.openhouse.tables.model.ViewModelConstants;
import com.linkedin.openhouse.tables.repository.OpenHouseInternalRepository;
import com.linkedin.openhouse.tables.services.ViewAdmissionService;
import com.linkedin.openhouse.tables.services.ViewsFeatureGate;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.apache.iceberg.io.InputFile;
import org.apache.iceberg.io.OutputFile;
import org.apache.iceberg.view.ViewMetadata;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * Client-owned input that only the real engine or its reserved properties can reject must reach the
 * caller as a 400, never a generic server fault. Real view repository, engine, codec and H2 HTS, in
 * a deployment configured for {@code spark,trino} so a source change is structurally legal.
 */
@SpringBootTest(
    classes = {SpringH2Application.class, ViewsClientRejectionH2IntegrationTest.Config.class},
    properties = "cluster.tables.views.supported-dialects=spark,trino")
@AutoConfigureMockMvc
@ContextConfiguration(
    initializers = {
      PropertyOverrideContextInitializer.class,
      AuthorizationPropertiesInitializer.class
    })
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
public class ViewsClientRejectionH2IntegrationTest {

  private static final String VIEWS_PATH =
      "/v1/databases/" + ViewModelConstants.DATABASE_ID + "/views";
  private static final String VIEW_PATH = VIEWS_PATH + "/" + ViewModelConstants.VIEW_ID;
  private static final String DROP_DIALECT_KEY = "replace.drop-dialect.allowed";
  private static final String SOURCE_MISMATCH_MESSAGE =
      "sourceDialect : must match the current view";
  private static final String SPARK = ViewModelConstants.SOURCE_DIALECT;
  private static final String TRINO = "trino";
  private static final String SECRET_SQL = "SELECT secret_column FROM my_database.my_table";

  @Autowired private MockMvc mvc;
  @Autowired private StorageManager storageManager;
  @Autowired private OpenHouseInternalRepository openHouseInternalRepository;

  // The H2 repository the view repository actually uses, spied to count reads and swaps.
  @SpyBean(name = "houseTablesH2Repository")
  private HouseTableRepository houseTableRepository;

  @SpyBean private ViewMetadataCodec viewMetadataCodec;
  @MockBean private ViewsFeatureGate viewsFeatureGate;
  @MockBean private AuditHandler<ViewAuditEvent> viewAuditHandler;
  @MockBean private ViewAdmissionService admissionService;

  // SpringH2Application defines bean "serviceAuditHandler"; ServiceAuditAspect injects by name.
  @MockBean(name = "serviceAuditHandler")
  private AuditHandler<ServiceAuditEvent> serviceAuditHandler;

  private String jwtAccessToken;

  @BeforeEach
  public void setup() throws Exception {
    jwtAccessToken =
        new DummyTokenInterceptor.DummySecurityJWT("DUMMY_ANONYMOUS_USER").buildNoopJWT();
    Mockito.when(viewsFeatureGate.isEnabled(ViewModelConstants.DATABASE_ID)).thenReturn(true);
    ensureDatabaseExists();
  }

  @AfterEach
  public void dropTheView() throws Exception {
    Mockito.reset(houseTableRepository, viewMetadataCodec);
    if (findViewRow() != null) {
      mvc.perform(withToken(MockMvcRequestBuilders.delete(VIEW_PATH))).andReturn();
    }
  }

  /** The engine owns this key; the API must reject it on create, whatever its value. */
  @ParameterizedTest
  @ValueSource(strings = {"true", "false"})
  public void createWithTheEngineOwnedDropDialectKeyIsRejectedBeforeTheService(String value)
      throws Exception {
    long filesBefore = metadataFileCount();

    MvcResult result =
        mvc.perform(
                withToken(
                    MockMvcRequestBuilders.post(VIEWS_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(withDropDialectKey(bothDialects(SPARK), value).toJson())))
            .andReturn();

    assertReservedKeyRejection(result);
    Assertions.assertEquals(filesBefore, metadataFileCount());
    Assertions.assertNull(findViewRow(), "a rejected create publishes nothing");
  }

  /** The same on replace: the existing view and its pointer are left untouched. */
  @ParameterizedTest
  @ValueSource(strings = {"true", "false"})
  public void replaceWithTheEngineOwnedDropDialectKeyIsRejectedBeforeTheService(String value)
      throws Exception {
    String base = create(bothDialects(SPARK));
    HouseTable before = findViewRow();
    long filesBefore = metadataFileCount();
    Mockito.clearInvocations(viewAuditHandler, serviceAuditHandler, admissionService);

    MvcResult result =
        mvc.perform(
                withToken(
                    MockMvcRequestBuilders.put(VIEW_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            withDropDialectKey(bothDialects(SPARK), value)
                                .toBuilder()
                                .baseMetadataLocation(base)
                                .build()
                                .toJson())))
            .andReturn();

    assertReservedKeyRejection(result);
    Assertions.assertEquals(filesBefore, metadataFileCount());
    Assertions.assertEquals(before, findViewRow(), "the pointer row must be untouched");
  }

  /**
   * Changing the stored source is structurally valid, so only the real engine guard can reject it:
   * after one read of the captured metadata, before any write or swap. Both representations are
   * kept so Iceberg's drop-dialect guard cannot be what fires.
   */
  @Test
  public void replaceThatChangesTheSourceDialectIsAClientRejection() throws Exception {
    String base = create(bothDialects(SPARK));
    HouseTable before = findViewRow();
    long filesBefore = metadataFileCount();
    Mockito.clearInvocations(
        viewAuditHandler, serviceAuditHandler, admissionService, houseTableRepository);
    Mockito.clearInvocations(viewMetadataCodec);

    MvcResult result;
    try (Log4j2LogCapture logs = new Log4j2LogCapture()) {
      result =
          mvc.perform(
                  withToken(
                      MockMvcRequestBuilders.put(VIEW_PATH)
                          .contentType(MediaType.APPLICATION_JSON)
                          .content(replaceOf(bothDialects(TRINO), base).toJson())))
              .andExpect(jsonPath("$.cause").doesNotExist())
              .andExpect(jsonPath("$.stacktrace").doesNotExist())
              .andReturn();
      Assertions.assertFalse(logs.renderedEvents().contains(SECRET_SQL), logs.renderedEvents());
    }

    String body = result.getResponse().getContentAsString();
    Assertions.assertEquals(400, result.getResponse().getStatus(), body);
    Assertions.assertEquals(
        SOURCE_MISMATCH_MESSAGE,
        com.jayway.jsonpath.JsonPath.read(body, "$.message"),
        "a fixed message, naming neither stored nor submitted values");
    Assertions.assertFalse(body.contains(SECRET_SQL), body);

    // Exactly one read of the captured file; no candidate write and no swap.
    Mockito.verify(viewMetadataCodec, Mockito.times(1)).read(any(InputFile.class));
    Mockito.verify(viewMetadataCodec, Mockito.never())
        .write(any(ViewMetadata.class), any(OutputFile.class));
    Mockito.verify(houseTableRepository, Mockito.never()).saveView(any());
    Assertions.assertEquals(filesBefore, metadataFileCount());
    Assertions.assertEquals(before, findViewRow(), "the captured pointer stays current");
    Mockito.verify(admissionService, Mockito.times(1)).admit(any());

    ViewAuditEvent operation = singleViewAudit();
    Assertions.assertEquals(OperationStatus.FAILED, operation.getOperationStatus());
    Assertions.assertEquals(before.getTableLocation(), operation.getOldMetadataLocation());
    Assertions.assertNull(operation.getNewMetadataLocation());
    AuditEventInspection.assertNoSensitiveProperties(singleServiceAudit(), SECRET_SQL, base);
  }

  /** Control: keeping the stored source is still an accepted, unchanged replace. */
  @Test
  public void replaceThatKeepsTheSourceDialectIsStillAcceptedAsANoOp() throws Exception {
    String base = create(bothDialects(SPARK));
    HouseTable before = findViewRow();
    long filesBefore = metadataFileCount();

    MvcResult result =
        mvc.perform(
                withToken(
                    MockMvcRequestBuilders.put(VIEW_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(replaceOf(bothDialects(SPARK), base).toJson())))
            .andReturn();

    Assertions.assertEquals(
        200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
    Assertions.assertEquals(
        base,
        com.jayway.jsonpath.JsonPath.read(
            result.getResponse().getContentAsString(), "$.metadataLocation"));
    Assertions.assertEquals(filesBefore, metadataFileCount());
    Assertions.assertEquals(before, findViewRow());
  }

  private void assertReservedKeyRejection(MvcResult result) throws Exception {
    String body = result.getResponse().getContentAsString();
    Assertions.assertEquals(400, result.getResponse().getStatus(), body);
    Assertions.assertEquals(
        "viewProperties : reserved keys are not allowed: " + DROP_DIALECT_KEY,
        com.jayway.jsonpath.JsonPath.read(body, "$.message"));
    Assertions.assertFalse(body.contains(SECRET_SQL), body);
    // Rejected by the API before the view service: no operation audit, admission or capture.
    Mockito.verify(viewAuditHandler, Mockito.never()).audit(any(ViewAuditEvent.class));
    Mockito.verifyNoInteractions(admissionService);
    AuditEventInspection.assertNoSensitiveProperties(singleServiceAudit(), SECRET_SQL);
  }

  private String create(CreateUpdateViewRequestBody body) throws Exception {
    MvcResult created =
        mvc.perform(
                withToken(
                    MockMvcRequestBuilders.post(VIEWS_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toJson())))
            .andReturn();
    Assertions.assertEquals(
        201, created.getResponse().getStatus(), created.getResponse().getContentAsString());
    return com.jayway.jsonpath.JsonPath.read(
        created.getResponse().getContentAsString(), "$.metadataLocation");
  }

  private static CreateUpdateViewRequestBody bothDialects(String source) {
    ViewRepresentation spark =
        ViewModelConstants.SPARK_REPRESENTATION.toBuilder().sql(SECRET_SQL).build();
    return ViewModelConstants.createRequestWithoutBaseVersion()
        .toBuilder()
        .representations(Arrays.asList(spark, spark.toBuilder().dialect(TRINO).build()))
        .sourceDialect(source)
        .build();
  }

  private static CreateUpdateViewRequestBody replaceOf(
      CreateUpdateViewRequestBody body, String base) {
    return body.toBuilder().baseMetadataLocation(base).build();
  }

  private static CreateUpdateViewRequestBody withDropDialectKey(
      CreateUpdateViewRequestBody body, String value) {
    Map<String, String> properties = new LinkedHashMap<>(body.getViewProperties());
    properties.put(DROP_DIALECT_KEY, value);
    return body.toBuilder().viewProperties(properties).build();
  }

  private MockHttpServletRequestBuilder withToken(MockHttpServletRequestBuilder builder) {
    return builder
        .accept(MediaType.APPLICATION_JSON)
        .header("Authorization", "Bearer " + jwtAccessToken);
  }

  private ViewAuditEvent singleViewAudit() {
    ArgumentCaptor<ViewAuditEvent> event = ArgumentCaptor.forClass(ViewAuditEvent.class);
    Mockito.verify(viewAuditHandler, Mockito.times(1)).audit(event.capture());
    return event.getValue();
  }

  private ServiceAuditEvent singleServiceAudit() {
    ArgumentCaptor<ServiceAuditEvent> event = ArgumentCaptor.forClass(ServiceAuditEvent.class);
    Mockito.verify(serviceAuditHandler, Mockito.times(1)).audit(event.capture());
    return event.getValue();
  }

  private HouseTable findViewRow() {
    return houseTableRepository
        .findViewById(
            HouseTablePrimaryKey.builder()
                .databaseId(ViewModelConstants.DATABASE_ID)
                .tableId(ViewModelConstants.VIEW_ID)
                .build())
        .orElse(null);
  }

  private void ensureDatabaseExists() {
    TableDto anchor =
        TableModelConstants.TABLE_DTO
            .toBuilder()
            .databaseId(ViewModelConstants.DATABASE_ID)
            .tableId("views_anchor_table")
            .tableVersion(ValidatorConstants.INITIAL_TABLE_VERSION)
            .build();
    TableDtoPrimaryKey key =
        TableDtoPrimaryKey.builder()
            .databaseId(anchor.getDatabaseId())
            .tableId(anchor.getTableId())
            .build();
    if (!openHouseInternalRepository.existsById(key)) {
      openHouseInternalRepository.save(anchor);
    }
  }

  private long metadataFileCount() throws Exception {
    Path rootPath = Paths.get(storageManager.getDefaultStorage().getClient().getRootPrefix());
    if (!Files.exists(rootPath)) {
      return 0;
    }
    try (Stream<Path> paths = Files.walk(rootPath)) {
      return paths
          .filter(Files::isRegularFile)
          .filter(path -> path.getFileName().toString().endsWith(".metadata.json"))
          .count();
    }
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
