package com.example.agentflow.common.error;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * A throwaway web layer used by {@link GlobalExceptionHandlerTest}. Its only job is to throw the
 * four failure modes the global handler must classify: {@code CONFLICT}, {@code NOT_FOUND}, {@code
 * 400} validation and an unexpected {@link RuntimeException}. It deliberately depends only on
 * Spring types (no domain type), so {@code common} stays compilable on itself.
 */
@RestController
@RequestMapping("/api/v1/test")
class StubErrorController {

  @GetMapping("/conflict")
  void conflict() {
    throw new ResponseStatusException(HttpStatus.CONFLICT, "resource already exists");
  }

  @GetMapping("/missing")
  void missing() {
    throw new ResponseStatusException(HttpStatus.NOT_FOUND, "resource not found");
  }

  @GetMapping("/internal")
  void internal() {
    throw new RuntimeException("postgres connection failed");
  }

  @PostMapping("/valid")
  void valid(@Valid @RequestBody ValidRequest body) {
    // Never reached in these tests; validation runs before the body is read.
  }
}

/** Minimal request DTO whose required field triggers a {@code 400} validation failure. */
class ValidRequest {

  @NotBlank(message = "name must not be blank")
  private String name;

  public ValidRequest() {}

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }
}
