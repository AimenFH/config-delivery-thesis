package at.technikum.thesis.runner;

import java.util.List;
import java.util.Optional;

public interface DeliveryAdapter {

    String name();

    List<ChangeTrigger.Result> deploy(RunnerClock clock);

    ChangeTrigger.Result reset(RunnerClock clock);

    ChangeTrigger.Result commit(Payload payload, RunnerClock clock);

    Optional<ChangeTrigger.Result> trigger(Payload payload, RunnerClock clock);

    Optional<ChangeTrigger.Result> sourceCheck(RunnerClock clock);

    List<ApproachDescriptor.Infrastructure> infrastructure();

    ApproachDescriptor.Declared declared();
}
