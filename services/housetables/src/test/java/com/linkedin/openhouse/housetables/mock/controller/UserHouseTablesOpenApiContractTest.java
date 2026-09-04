package com.linkedin.openhouse.housetables.mock.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * Asserts the document springdoc actually emits, because a non-{@code @Hidden} advice or a
 * springdoc convention could change the contract without changing any annotation.
 *
 * <p>{@code UserHouseTablesControllerApiContractTest} pins the same surface at the annotation
 * level; a disagreement between the two is springdoc's behaviour, which is what this test exists to
 * catch.
 */
@SpringBootTest
@AutoConfigureMockMvc
public class UserHouseTablesOpenApiContractTest {

  @Autowired private MockMvc mvc;

  private static final Map<String, Set<String>> EXPECTED_RESPONSE_CODES = frozenContract();

  private static Map<String, Set<String>> frozenContract() {
    Map<String, Set<String>> expected = new TreeMap<>();
    expected.put("get /hts/tables", codes("200", "404"));
    expected.put("get /hts/tables/query", codes("200"));
    expected.put("get /v1/hts/tables/query", codes("200"));
    expected.put("get /hts/tables/querySoftDeleted", codes("200", "400", "404"));
    expected.put("delete /hts/tables", codes("204", "400", "404"));
    expected.put("delete /v1/hts/tables", codes("204", "400", "404"));
    expected.put("put /hts/tables", codes("200", "201", "400", "404", "409"));
    expected.put("patch /hts/tables/rename", codes("204", "400", "404", "409"));
    expected.put("put /hts/tables/restore", codes("204", "400", "404", "409"));
    expected.put("delete /hts/tables/purge", codes("204", "400", "404"));
    expected.put("get /hts/entities", codes("200", "400", "404", "500"));
    expected.put("get /hts/views", codes("200", "400", "404", "500"));
    expected.put("get /v1/hts/views/query", codes("200", "400", "500"));
    expected.put("put /hts/views", codes("200", "201", "400", "409"));
    expected.put("delete /hts/views", codes("204", "400", "404"));
    return expected;
  }

  private static Set<String> codes(String... values) {
    return new LinkedHashSet<>(Arrays.asList(values));
  }

  private Map<String, Set<String>> generatedUserTableOperations() throws Exception {
    String document =
        mvc.perform(MockMvcRequestBuilders.get("/v3/api-docs"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    JsonObject paths = JsonParser.parseString(document).getAsJsonObject().getAsJsonObject("paths");
    Map<String, Set<String>> operations = new TreeMap<>();
    for (String path : paths.keySet()) {
      JsonObject methods = paths.getAsJsonObject(path);
      for (String method : methods.keySet()) {
        JsonObject operation = methods.getAsJsonObject(method);
        if (!isUserTableOperation(operation)) {
          continue;
        }
        operations.put(
            method + " " + path,
            new LinkedHashSet<>(operation.getAsJsonObject("responses").keySet()));
      }
    }
    return operations;
  }

  private static boolean isUserTableOperation(JsonObject operation) {
    if (!operation.has("tags")) {
      return false;
    }
    for (com.google.gson.JsonElement tag : operation.getAsJsonArray("tags")) {
      if ("UserTable".equals(tag.getAsString())) {
        return true;
      }
    }
    return false;
  }

  private JsonObject generatedUserTableSchema() throws Exception {
    String document =
        mvc.perform(MockMvcRequestBuilders.get("/v3/api-docs"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    JsonObject schemas =
        JsonParser.parseString(document)
            .getAsJsonObject()
            .getAsJsonObject("components")
            .getAsJsonObject("schemas");
    assertThat(schemas.keySet()).contains("UserTable");
    return schemas.getAsJsonObject("UserTable");
  }

  /** The route-side stamp is a server behaviour, not a contract permission to omit the field. */
  @Test
  public void testUserTableSchemaDeclaresEntityTypeAsRequired() throws Exception {
    JsonObject userTable = generatedUserTableSchema();

    assertThat(userTable.has("required"))
        .withFailMessage("UserTable declares no required fields at all")
        .isTrue();
    Set<String> required = new LinkedHashSet<>();
    userTable.getAsJsonArray("required").forEach(field -> required.add(field.getAsString()));

    assertThat(required).contains("entityType");
  }

  /** The old description told callers that omitting the field meant TABLE. */
  @Test
  public void testEntityTypeDescriptionNoLongerOffersNullAsASpelling() throws Exception {
    JsonObject entityType =
        generatedUserTableSchema().getAsJsonObject("properties").getAsJsonObject("entityType");

    String description = entityType.get("description").getAsString();
    // Case-insensitive: "TABLE or null" and "may be null" offer the same removed spelling.
    assertThat(description).doesNotContainIgnoringCase("null");
    assertThat(description).containsIgnoringCase("case-insensitiv");
  }

  @Test
  public void testGeneratedDocumentDeclaresExactlyTheFrozenResponseCodes() throws Exception {
    assertThat(generatedUserTableOperations())
        .containsExactlyInAnyOrderEntriesOf(EXPECTED_RESPONSE_CODES);
  }

  /**
   * The five operations the generated client gains, named individually so a gap is legible. Views
   * are paginated-only, so there is no unpaginated {@code /hts/views/query} counterpart to the
   * table route.
   */
  @Test
  public void testTheFiveNewOperationsAreGenerated() throws Exception {
    assertThat(generatedUserTableOperations().keySet())
        .contains(
            "get /hts/entities",
            "get /hts/views",
            "get /v1/hts/views/query",
            "put /hts/views",
            "delete /hts/views")
        .doesNotContain("get /hts/views/query");
  }
}
