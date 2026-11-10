package com.example.agentflow.ai.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.agentflow.ai.application.ChatModelPort;
import com.example.agentflow.ai.application.PlannerPlanResult;
import com.example.agentflow.ai.application.PlannerService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * S09-11 end-to-end evidence: the real application context boots with {@code
 * agentflow.ai.provider=fake} and the {@link PlannerService} proposes a plan through the wired
 * {@link FakeModelProvider} — no API key, no provider profile, no network. This is the acceptance
 * hook for the standard CI gate: what runs here is exactly what {@code ./gradlew check} runs.
 *
 * <p>The class and its methods are public: JUnit 6.0 follows standard Java visibility semantics
 * during discovery, and the Jupiter engine lives in a different package, so package-private test
 * members would not be discovered (the same rule as {@code AgentflowApplicationTests}).
 */
@SpringBootTest(properties = "agentflow.ai.provider=fake")
public class FakeProviderPlannerEndToEndTest {

  private final PlannerService plannerService;
  private final ChatModelPort chatModelPort;

  @Autowired
  public FakeProviderPlannerEndToEndTest(
      PlannerService plannerService, ChatModelPort chatModelPort) {
    this.plannerService = plannerService;
    this.chatModelPort = chatModelPort;
  }

  @Test
  public void the_wired_port_is_the_fake_provider() {
    assertThat(chatModelPort).isInstanceOf(FakeModelProvider.class);
    assertThat(((FakeModelProvider) chatModelPort).fixture())
        .isEqualTo(FakeModelProvider.Fixture.VALID_PLAN);
  }

  @Test
  public void the_planner_proposes_a_plan_through_the_fake_provider_without_any_key_or_network() {
    PlannerPlanResult result = plannerService.propose("compute 2 + 2", List.of("calculator"));

    assertThat(result.plan().steps()).hasSize(2);
    assertThat(result.plan().steps().get(0).id()).isEqualTo("s1");
    assertThat(result.plan().steps().get(1).dependsOn()).containsExactly("s1");
    assertThat(result.model()).isEqualTo(FakeModelProvider.MODEL);
    assertThat(result.promptVersion().name()).isEqualTo("planner");
    assertThat(((FakeModelProvider) chatModelPort).calls()).isEqualTo(1);
  }
}
