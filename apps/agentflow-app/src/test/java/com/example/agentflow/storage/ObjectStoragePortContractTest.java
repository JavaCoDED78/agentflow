package com.example.agentflow.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Contract unit tests for the {@link ObjectStoragePort} contract itself (S07-06B, contract §1, §2.3
 * and §9 cases 1 and 11 — the normative method shape and provider neutrality).
 *
 * <p>These tests pin the contract's normative surface so a future adapter cannot silently drift:
 * the port exposes exactly the five operations with the contract's normative signatures, every
 * public type in the root package is one of the sanctioned contract types, and no AWS SDK or Garage
 * class appears anywhere in the public surface.
 */
class ObjectStoragePortContractTest {

  private static final String ROOT_PACKAGE = "com.example.agentflow.storage";
  private static final String CLASSES_DIR = "build/classes/java/main/com/example/agentflow/storage";

  private static final List<Class<?>> SANCTIONED_TYPES =
      List.of(
          ObjectStoragePort.class,
          ObjectReference.class,
          ObjectMetadata.class,
          CreateObjectRequest.class,
          OpenedObject.class,
          ObjectListPage.class,
          DeleteResult.class,
          ObjectStorageException.class,
          ObjectStorageErrorCode.class);

  @Test
  void the_port_exposes_exactly_five_operations_with_the_contract_signatures() {
    Set<String> operations = new LinkedHashSet<>();
    for (Method method : ObjectStoragePort.class.getMethods()) {
      if (method.getDeclaringClass() != ObjectStoragePort.class) continue;
      if (method.isSynthetic()) continue;
      operations.add(method.getName());

      if (method.getName().equals("put")) {
        assertThat(method.getParameterTypes()).containsExactly(CreateObjectRequest.class);
        assertThat(method.getReturnType()).isEqualTo(ObjectReference.class);
      } else if (method.getName().equals("get")) {
        assertThat(method.getParameterTypes()).containsExactly(String.class);
        assertThat(method.getReturnType()).isEqualTo(OpenedObject.class);
      } else if (method.getName().equals("head")) {
        assertThat(method.getParameterTypes()).containsExactly(String.class);
        assertThat(method.getReturnType()).isEqualTo(java.util.Optional.class);
      } else if (method.getName().equals("list")) {
        assertThat(method.getParameterTypes())
            .containsExactly(String.class, String.class, int.class);
        assertThat(method.getReturnType()).isEqualTo(ObjectListPage.class);
      } else if (method.getName().equals("delete")) {
        assertThat(method.getParameterTypes()).containsExactly(String.class);
        assertThat(method.getReturnType()).isEqualTo(DeleteResult.class);
      } else {
        throw new AssertionError("unexpected port operation: " + method.getName());
      }
    }

    assertThat(operations).containsExactlyInAnyOrder("put", "get", "head", "list", "delete");
  }

  @Test
  void the_head_operation_returns_an_optional() {
    for (Method method : ObjectStoragePort.class.getMethods()) {
      if (method.getName().equals("head")) {
        String returnType = method.getReturnType().getName();
        assertThat(returnType).startsWith(java.util.Optional.class.getName());
        return;
      }
    }
    throw new AssertionError("head operation not found");
  }

  @Test
  void the_public_surface_of_the_root_package_is_exactly_the_sanctioned_contract_types()
      throws Exception {
    Path classesDir = Path.of(CLASSES_DIR);
    assertThat(Files.isDirectory(classesDir))
        .as("compiled storage classes dir must exist — run the test through Gradle")
        .isTrue();

    Set<String> publicTypes = new LinkedHashSet<>();
    try (Stream<Path> entries = Files.list(classesDir)) {
      for (Path entry : entries.filter(p -> p.toString().endsWith(".class")).toList()) {
        String fileName = entry.getFileName().toString();
        if (fileName.contains("$")) continue; // package-info and nested classes are not top-level
        String className = ROOT_PACKAGE + "." + fileName.replace(".class", "");
        Class<?> type = Class.forName(className);
        if (Modifier.isPublic(type.getModifiers())) {
          publicTypes.add(type.getSimpleName());
        }
      }
    }

    List<String> sanctioned = SANCTIONED_TYPES.stream().map(Class::getSimpleName).sorted().toList();
    assertThat(publicTypes.stream().sorted().toList()).containsExactlyElementsOf(sanctioned);
  }

  @Test
  void no_public_signature_references_an_aws_sdk_or_garage_type() {
    for (Class<?> type : SANCTIONED_TYPES) {
      for (Method method : type.getMethods()) {
        assertTypeIsProviderNeutral(
            method.getParameterTypes(), method.getReturnType(), type, method.getName());
      }
      for (Constructor<?> constructor : type.getConstructors()) {
        assertTypeIsProviderNeutral(constructor.getParameterTypes(), null, type, "<init>");
      }
      for (java.lang.reflect.Field field : type.getDeclaredFields()) {
        assertTypeIsProviderNeutral(new Class<?>[] {field.getType()}, null, type, field.getName());
      }
    }
  }

  @Test
  void the_error_taxonomy_classifies_retryability_honestly() {
    for (ObjectStorageErrorCode code : ObjectStorageErrorCode.values()) {
      boolean expected =
          code == ObjectStorageErrorCode.UNAVAILABLE || code == ObjectStorageErrorCode.TIMEOUT;
      assertThat(code.retryable()).as("%s retryable", code).isEqualTo(expected);
    }
  }

  @Test
  void the_delete_result_has_exactly_the_two_contract_outcomes() {
    assertThat(Arrays.toString(DeleteResult.values())).contains("DELETED", "ALREADY_ABSENT");
    assertThat(Arrays.asList(DeleteResult.values())).hasSize(2);
  }

  private static void assertTypeIsProviderNeutral(
      Class<?>[] parameterTypes, Class<?> returnType, Class<?> type, String member) {
    for (Class<?> parameterType : parameterTypes == null ? new Class<?>[] {} : parameterTypes) {
      assertTypeIsProviderNeutral(parameterType, type, member);
    }
    if (returnType != null) {
      assertTypeIsProviderNeutral(returnType, type, member);
    }
  }

  private static void assertTypeIsProviderNeutral(Class<?> type, Class<?> owner, String member) {
    String name = type.getName();
    assertThat(name)
        .as(
            "%s.%s must not expose an AWS SDK / Garage type (%s)",
            owner.getSimpleName(), member, name)
        .doesNotContain("software.amazon")
        .doesNotContain("garage");
  }
}
