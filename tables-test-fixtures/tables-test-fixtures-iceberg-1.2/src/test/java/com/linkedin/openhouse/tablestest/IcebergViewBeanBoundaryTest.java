package com.linkedin.openhouse.tablestest;

import com.linkedin.openhouse.internal.catalog.OpenHouseInternalCatalog;
import com.linkedin.openhouse.internal.catalog.view.OpenHouseInternalViewRepository;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.iceberg.catalog.Catalog;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Guards the Iceberg 1.2 / 1.5 boundary for the view commit repository.
 *
 * <p>This class lives in the 1.2 fixture's test sources, which the 1.5 fixture also compiles and
 * runs, so the same assertions execute against both Iceberg versions and the expectation is chosen
 * at runtime from what is actually on the classpath.
 *
 * <p>The boundary matters because the fixture component-scans {@code
 * com.linkedin.openhouse.internal.catalog}. Under Iceberg 1.2 there is no {@code
 * org.apache.iceberg.view} package at all, so a view-typed bean, or a view type anywhere on a
 * shared bean's signature, makes Spring fail while introspecting rather than at the call site —
 * which is why the check below is reflective and covers every bean, not just the view ones.
 */
public class IcebergViewBeanBoundaryTest {

  private static final String VIEW_PACKAGE_PREFIX = "org.apache.iceberg.view.";
  private static final String VIEW_METADATA_CLASS = "org.apache.iceberg.view.ViewMetadata";
  private static final String VIEW_CODEC_CLASS =
      "com.linkedin.openhouse.internal.catalog.view.ViewMetadataCodec";
  private static final String VIEW_REPOSITORY_IMPL_CLASS =
      "com.linkedin.openhouse.internal.catalog.view.OpenHouseInternalViewRepositoryImpl";

  private static boolean icebergViewApiPresent() {
    try {
      Class.forName(VIEW_METADATA_CLASS);
      return true;
    } catch (ClassNotFoundException e) {
      return false;
    }
  }

  private ConfigurableApplicationContext boot() {
    try {
      org.apache.catalina.webresources.TomcatURLStreamHandlerFactory.register();
    } catch (Error e) {
      org.apache.catalina.webresources.TomcatURLStreamHandlerFactory.disable();
    }
    SpringApplication application = new SpringApplication(SpringH2TestApplication.class);
    application.setDefaultProperties(Collections.singletonMap("server.port", "0"));
    return application.run();
  }

  /**
   * Under 1.2 the conditional configuration is skipped entirely, so no repository or codec bean
   * exists. Under 1.5 there is exactly one of each.
   */
  @Test
  public void viewCommitBeansExistOnlyWhereTheIcebergViewApiDoes() {
    try (ConfigurableApplicationContext context = boot()) {
      String[] repositoryBeans = context.getBeanNamesForType(OpenHouseInternalViewRepository.class);

      if (icebergViewApiPresent()) {
        Assertions.assertEquals(
            1,
            repositoryBeans.length,
            "expected exactly one view commit repository under Iceberg 1.5, found "
                + Arrays.toString(repositoryBeans));
        Assertions.assertEquals(
            1,
            context.getBeanNamesForType(loadOrFail(VIEW_CODEC_CLASS)).length,
            "expected exactly one view metadata codec under Iceberg 1.5");
        Assertions.assertNotNull(
            context.getBean(loadOrFail(VIEW_REPOSITORY_IMPL_CLASS)),
            "the 1.5 repository implementation must be the registered bean");
      } else {
        Assertions.assertEquals(
            0,
            repositoryBeans.length,
            "no view commit repository may exist under Iceberg 1.2, found "
                + Arrays.toString(repositoryBeans));
        for (String name : context.getBeanDefinitionNames()) {
          Assertions.assertFalse(
              name.toLowerCase().contains("viewmetadatacodec"),
              "no view metadata codec bean may exist under Iceberg 1.2: " + name);
        }
      }
    }
  }

  /**
   * The real hazard is a shared bean quietly growing a view type on a method, constructor, or
   * field. That is invisible under 1.5 and fatal under 1.2, so every bean's signature is walked
   * here.
   *
   * <p>The walk is over {@link Type}, not erased {@link Class}, because {@code List<ViewMetadata>}
   * erases to {@code List} and would otherwise slip through; it includes inherited public methods
   * because a leak can be inherited; and it treats a resolution failure during inspection as an
   * offender, since that is exactly the Spring-introspection failure this test exists to prevent.
   */
  @Test
  public void noSharedBeanSignatureNamesAnIcebergViewType() {
    try (ConfigurableApplicationContext context = boot()) {
      List<String> offenders = new ArrayList<>();
      for (String name : context.getBeanDefinitionNames()) {
        Class<?> type = context.getType(name);
        if (type == null || isViewScopedBean(type)) {
          continue;
        }
        inspect(offenders, name, type);
      }
      Assertions.assertTrue(
          offenders.isEmpty(),
          "shared Spring beans must not name org.apache.iceberg.view types: " + offenders);
    }
  }

  private static void inspect(List<String> offenders, String beanName, Class<?> type) {
    safely(
        offenders,
        beanName,
        type,
        () -> {
          for (Method method : type.getDeclaredMethods()) {
            record(offenders, beanName, type, method.getGenericReturnType());
            for (Type parameter : method.getGenericParameterTypes()) {
              record(offenders, beanName, type, parameter);
            }
          }
        });
    // Inherited public API is just as visible to Spring as declared API.
    safely(
        offenders,
        beanName,
        type,
        () -> {
          for (Method method : type.getMethods()) {
            record(offenders, beanName, type, method.getGenericReturnType());
            for (Type parameter : method.getGenericParameterTypes()) {
              record(offenders, beanName, type, parameter);
            }
          }
        });
    safely(
        offenders,
        beanName,
        type,
        () -> {
          for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            for (Type parameter : constructor.getGenericParameterTypes()) {
              record(offenders, beanName, type, parameter);
            }
          }
        });
    safely(
        offenders,
        beanName,
        type,
        () -> {
          for (Field field : type.getDeclaredFields()) {
            record(offenders, beanName, type, field.getGenericType());
          }
        });
    safely(
        offenders,
        beanName,
        type,
        () -> {
          for (TypeVariable<?> variable : type.getTypeParameters()) {
            for (Type bound : variable.getBounds()) {
              record(offenders, beanName, type, bound);
            }
          }
        });
  }

  /**
   * Reflection resolves a whole member category at once, so one unresolvable member blinds the scan
   * for every other member of that bean. Swallowing such a failure would let a genuine view leak on
   * a sibling member go unexamined, so a failure is never simply ignored:
   *
   * <ol>
   *   <li>if the unresolvable type is itself an Iceberg view type, that is the leak;
   *   <li>otherwise the bean's class file is scanned directly, which needs no type resolution at
   *       all and therefore cannot be blinded by the same missing dependency;
   *   <li>only a bean on the narrow optional-dependency allowlist may then be tolerated. Anything
   *       else is reported, because "we could not check it" is not the same as "it is clean".
   * </ol>
   */
  private static void safely(
      List<String> offenders, String beanName, Class<?> type, Runnable inspection) {
    try {
      inspection.run();
    } catch (TypeNotPresentException | NoClassDefFoundError e) {
      handleInspectionFailure(offenders, beanName, type, e, VIEW_PACKAGE_PREFIX);
    }
  }

  static void handleInspectionFailure(
      List<String> offenders, String beanName, Class<?> type, Throwable failure, String prefix) {
    String detail = String.valueOf(failure.getMessage());
    String slashed = prefix.replace('.', '/');
    if (detail.contains(prefix) || detail.contains(slashed)) {
      offenders.add(
          beanName
              + " ("
              + type.getName()
              + ") has a signature naming an absent type in "
              + prefix
              + ": "
              + detail);
      return;
    }

    Boolean referencesPrefix = classFileReferences(type, prefix);
    if (Boolean.TRUE.equals(referencesPrefix)) {
      offenders.add(
          beanName
              + " ("
              + type.getName()
              + ") references "
              + prefix
              + " in its class file, found by the class-file fallback after reflection failed on: "
              + detail);
      return;
    }
    if (referencesPrefix == null) {
      offenders.add(
          beanName
              + " ("
              + type.getName()
              + ") could not be inspected reflectively or read as a class file: "
              + detail);
      return;
    }
    if (!isKnownOptionalDependencyGap(type, detail)) {
      offenders.add(
          beanName
              + " ("
              + type.getName()
              + ") is not inspectable and is not an allowlisted optional-dependency bean: "
              + detail);
    }
  }

  /**
   * Reads the bean's own class file and looks for the package in its constant pool. Descriptors and
   * generic signatures are stored there as plain text, so this sees every signature the class
   * declares without asking the class loader to resolve anything.
   *
   * @return true when found, false when definitely absent, null when the class file is unreadable
   */
  static Boolean classFileReferences(Class<?> type, String prefix) {
    String resource = type.getName().replace('.', '/') + ".class";
    ClassLoader loader =
        type.getClassLoader() == null ? ClassLoader.getSystemClassLoader() : type.getClassLoader();
    try (InputStream stream = loader.getResourceAsStream(resource)) {
      if (stream == null) {
        return null;
      }
      ByteArrayOutputStream buffer = new ByteArrayOutputStream();
      byte[] chunk = new byte[8192];
      int read;
      while ((read = stream.read(chunk)) != -1) {
        buffer.write(chunk, 0, read);
      }
      String constantPool = new String(buffer.toByteArray(), StandardCharsets.ISO_8859_1);
      return constantPool.contains(prefix.replace('.', '/')) || constantPool.contains(prefix);
    } catch (IOException e) {
      return null;
    }
  }

  /**
   * The only beans allowed to be uninspectable, and only for the exact dependency that is missing.
   * Anything broader would re-open the masking hole this allowlist exists inside of.
   */
  static boolean isKnownOptionalDependencyGap(Class<?> type, String detail) {
    String missing = detail.toLowerCase(java.util.Locale.ROOT);
    for (Map.Entry<String, String> allowed : OPTIONAL_DEPENDENCY_GAPS.entrySet()) {
      if (type.getName().equals(allowed.getKey()) && missing.contains(allowed.getValue())) {
        return true;
      }
    }
    return false;
  }

  /** Bean class name to the lowercase fragment of the optional dependency it may be missing. */
  private static final Map<String, String> OPTIONAL_DEPENDENCY_GAPS = optionalDependencyGaps();

  private static Map<String, String> optionalDependencyGaps() {
    Map<String, String> gaps = new HashMap<>();
    // springdoc's Querydsl integration is registered unconditionally but Querydsl itself is not a
    // dependency of this fixture, so its signatures cannot resolve on either Iceberg version.
    gaps.put(
        "org.springdoc.data.rest.customisers.QuerydslPredicateOperationCustomizer", "querydsl");
    return gaps;
  }

  private static void record(
      List<String> offenders, String beanName, Class<?> beanType, Type candidate) {
    record(offenders, beanName, beanType, candidate, VIEW_PACKAGE_PREFIX, new HashSet<Type>());
  }

  /** Walks parameterized types, arrays, wildcards, and type-variable bounds to their components. */
  private static void record(
      List<String> offenders,
      String beanName,
      Class<?> beanType,
      Type candidate,
      String prefix,
      Set<Type> seen) {
    if (candidate == null || !seen.add(candidate)) {
      return;
    }
    if (candidate instanceof Class<?>) {
      Class<?> component = (Class<?>) candidate;
      while (component.isArray()) {
        component = component.getComponentType();
      }
      if (component.getName().startsWith(prefix)) {
        offenders.add(beanName + " (" + beanType.getName() + ") names " + component.getName());
      }
      return;
    }
    if (candidate instanceof ParameterizedType) {
      ParameterizedType parameterized = (ParameterizedType) candidate;
      record(offenders, beanName, beanType, parameterized.getRawType(), prefix, seen);
      for (Type argument : parameterized.getActualTypeArguments()) {
        record(offenders, beanName, beanType, argument, prefix, seen);
      }
      return;
    }
    if (candidate instanceof GenericArrayType) {
      record(
          offenders,
          beanName,
          beanType,
          ((GenericArrayType) candidate).getGenericComponentType(),
          prefix,
          seen);
      return;
    }
    if (candidate instanceof WildcardType) {
      WildcardType wildcard = (WildcardType) candidate;
      for (Type bound : wildcard.getUpperBounds()) {
        record(offenders, beanName, beanType, bound, prefix, seen);
      }
      for (Type bound : wildcard.getLowerBounds()) {
        record(offenders, beanName, beanType, bound, prefix, seen);
      }
      return;
    }
    if (candidate instanceof TypeVariable) {
      for (Type bound : ((TypeVariable<?>) candidate).getBounds()) {
        record(offenders, beanName, beanType, bound, prefix, seen);
      }
    }
  }

  /** The table catalog stays the one unqualified Iceberg catalog on both versions. */
  @Test
  public void unqualifiedCatalogStillResolvesToTheTableCatalog() {
    try (ConfigurableApplicationContext context = boot()) {
      Assertions.assertTrue(
          context.getBean(Catalog.class) instanceof OpenHouseInternalCatalog,
          "an unqualified Catalog lookup must keep resolving to OpenHouseInternalCatalog");
    }
  }

  /**
   * The audit above is only worth anything if the walker actually descends into generic arguments,
   * arrays, and bounds; an erased walk over {@code Class} would silently pass everything. This runs
   * the same walker against a probe with known nested types, using a prefix that exists on both
   * Iceberg versions so the proof itself is version-neutral.
   */
  @Test
  public void theSignatureWalkerDescendsIntoGenericsArraysAndBounds() {
    String probedPrefix = "java.util.concurrent.";
    List<String> offenders = new ArrayList<>();
    for (Method method : NestedTypeProbe.class.getDeclaredMethods()) {
      record(
          offenders,
          "probe",
          NestedTypeProbe.class,
          method.getGenericReturnType(),
          probedPrefix,
          new HashSet<>());
      for (Type parameter : method.getGenericParameterTypes()) {
        record(
            offenders,
            "probe",
            NestedTypeProbe.class,
            parameter,
            probedPrefix,
            new HashSet<Type>());
      }
    }
    for (Field field : NestedTypeProbe.class.getDeclaredFields()) {
      record(
          offenders,
          "probe",
          NestedTypeProbe.class,
          field.getGenericType(),
          probedPrefix,
          new HashSet<>());
    }

    Assertions.assertTrue(
        offenders.stream().anyMatch(offender -> offender.contains("java.util.concurrent.Callable")),
        "a type nested inside a generic argument must be found: " + offenders);
    Assertions.assertTrue(
        offenders.stream().anyMatch(offender -> offender.contains("java.util.concurrent.Future")),
        "a type nested inside an array of generics must be found: " + offenders);
    Assertions.assertTrue(
        offenders.stream().anyMatch(offender -> offender.contains("java.util.concurrent.TimeUnit")),
        "a type nested inside a wildcard bound must be found: " + offenders);
    Assertions.assertTrue(
        offenders.stream()
            .anyMatch(offender -> offender.contains("java.util.concurrent.ExecutorService")),
        "a type nested inside a type-variable bound must be found: " + offenders);

    List<String> clean = new ArrayList<>();
    for (Field field : CleanProbe.class.getDeclaredFields()) {
      record(
          clean,
          "clean",
          CleanProbe.class,
          field.getGenericType(),
          probedPrefix,
          new HashSet<Type>());
    }
    Assertions.assertTrue(clean.isEmpty(), "a clean signature must not be flagged: " + clean);
  }

  /**
   * The masking path: reflection fails on one member for an unrelated missing dependency, while a
   * different member of the same bean names a type we must not miss. The class-file fallback needs
   * no type resolution, so the leak is still reported instead of being swallowed with the failure.
   */
  @Test
  public void anUnrelatedResolutionFailureCannotHideALeakOnAnotherMember() {
    String probedPrefix = "java.util.concurrent.";
    NoClassDefFoundError unrelated = new NoClassDefFoundError("com/querydsl/core/types/Predicate");

    // NestedTypeProbe's class file references java.util.concurrent types on several members.
    Assertions.assertEquals(
        Boolean.TRUE,
        classFileReferences(NestedTypeProbe.class, probedPrefix),
        "the fallback must see the reference without resolving anything");

    List<String> offenders = new ArrayList<>();
    handleInspectionFailure(offenders, "leaky", NestedTypeProbe.class, unrelated, probedPrefix);
    Assertions.assertEquals(
        1,
        offenders.size(),
        "an unrelated linkage failure must not suppress a leak on another member: " + offenders);
    Assertions.assertTrue(offenders.get(0).contains("class-file fallback"), offenders.get(0));

    // A bean that genuinely does not reference the package, and is not allowlisted, is still
    // reported: "could not check" is not "clean".
    List<String> unverifiable = new ArrayList<>();
    handleInspectionFailure(unverifiable, "unknownBean", CleanProbe.class, unrelated, probedPrefix);
    Assertions.assertEquals(1, unverifiable.size(), String.valueOf(unverifiable));
    Assertions.assertTrue(unverifiable.get(0).contains("not an allowlisted"), unverifiable.get(0));

    // The failure that names the searched package directly is reported without needing the
    // fallback.
    List<String> direct = new ArrayList<>();
    handleInspectionFailure(
        direct,
        "direct",
        CleanProbe.class,
        new NoClassDefFoundError("java/util/concurrent/Callable"),
        probedPrefix);
    Assertions.assertEquals(1, direct.size(), String.valueOf(direct));
  }

  /**
   * The allowlist is keyed on both the exact bean type and the exact dependency that is missing.
   */
  @Test
  public void theOptionalDependencyAllowlistIsNarrow() {
    Assertions.assertFalse(
        isKnownOptionalDependencyGap(CleanProbe.class, "com/querydsl/core/types/Predicate"),
        "an arbitrary bean may not ride the allowlist");
  }

  @SuppressWarnings("unused")
  private static final class NestedTypeProbe {
    private List<java.util.concurrent.Callable<String>> nestedInGenericArgument;
    private List<? extends java.util.concurrent.TimeUnit> nestedInWildcardBound;

    private java.util.concurrent.Future<String>[] nestedInArrayOfGenerics() {
      return null;
    }

    private <T extends java.util.concurrent.ExecutorService> void nestedInTypeVariableBound(T t) {}
  }

  @SuppressWarnings("unused")
  private static final class CleanProbe {
    private List<String> plainGeneric;
    private String[] plainArray;
  }

  /** The conditional view beans are the only ones allowed to name Iceberg view types. */
  private static boolean isViewScopedBean(Class<?> type) {
    return type.getName().startsWith("com.linkedin.openhouse.internal.catalog.view.")
        && (type.getName().endsWith("ViewMetadataCodec")
            || type.getName().endsWith("OpenHouseInternalViewRepositoryImpl"));
  }

  private static Class<?> loadOrFail(String className) {
    try {
      return Class.forName(className);
    } catch (ClassNotFoundException e) {
      throw new AssertionError("expected " + className + " on the Iceberg 1.5 classpath", e);
    }
  }
}
