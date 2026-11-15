package at.technikum.thesis.runner;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class CommandAdapterTest {

    private final List<List<String>> commands = new ArrayList<>();
    private final RunnerClock clock = new RunnerClock();
    private int exitCode = 0;
    private final CommandRunner fake = (argv, usedClock) -> {
        assertSame(clock, usedClock);
        commands.add(argv);
        return new ChangeTrigger.Result(String.join(" ", argv), 10, 25, exitCode, "done");
    };

    @Test
    void commitReplacesVersionAndTimeoutInEveryElement() throws Exception {
        ChangeTrigger.Result result = adapter("way-c").commit(new Payload("v2", "abc"), clock);

        assertEquals(List.of(List.of("bash", "tools/way-c-commit.sh", "v2", "abc")), commands);
        assertEquals(new ChangeTrigger.Result("bash tools/way-c-commit.sh v2 abc", 10, 25, 0, "done"), result);
    }

    @Test
    void commitReplacesPlaceholdersInsideAnElement() throws Exception {
        adapter("way-b").commit(new Payload("v3", "1000"), clock);

        assertEquals(List.of(List.of("kubectl", "-n", "thesis", "patch", "configmap", "demo-config", "--type", "merge",
                "-p", "{\"data\":{\"demo.configVersion\":\"v3\",\"demo.timeoutMs\":\"1000\"}}")), commands);
    }

    @Test
    void resetUsesTheInitialValuesOfTheProtocol() throws Exception {
        adapter("way-a").reset(clock);
        adapter("way-c").reset(clock);

        assertEquals(List.of(
                List.of("kubectl", "-n", "thesis", "patch", "configmap", "demo-config", "--type", "merge",
                        "-p", "{\"data\":{\"configVersion\":\"v1\",\"timeoutMs\":\"1000\"}}"),
                List.of("bash", "tools/way-c-commit.sh", "v1", "1000")), commands);
    }

    @Test
    void allFourPlaceholdersAreReplacedTogether() throws Exception {
        ApproachDescriptor descriptor = new ApproachDescriptor("x", "X", List.of("m"),
                List.of("r", "{initialVersion}", "{initialTimeoutMs}"),
                List.of("c", "{initialVersion}>{version}", "{initialTimeoutMs}>{timeoutMs}", "{version}{version}"),
                List.of("t", "{version}", "{timeoutMs}", "{initialVersion}"), null,
                List.of(new ApproachDescriptor.Infrastructure("ns", "d")),
                new ApproachDescriptor.Declared(List.of(), 0, false, "none"));
        CommandAdapter adapter = new CommandAdapter(descriptor, Fixtures.protocol(), fake);

        adapter.commit(new Payload("v7", "250"), clock);
        adapter.trigger(new Payload("v7", "250"), clock);

        assertEquals(List.of(List.of("c", "v1>v7", "1000>250", "v7v7"), List.of("t", "v7", "250", "v1")), commands);
    }

    @Test
    void deployAppliesEveryManifestsEntry() throws Exception {
        ApproachDescriptor wayA = descriptor("way-a");
        ApproachDescriptor two = new ApproachDescriptor(wayA.name(), wayA.title(), List.of("deploy/one", "deploy/two"),
                wayA.reset(), wayA.commit(), null, null, wayA.infrastructure(), wayA.declared());

        List<ChangeTrigger.Result> results = new CommandAdapter(two, Fixtures.protocol(), fake).deploy(clock);

        assertEquals(List.of(List.of("kubectl", "apply", "-f", "deploy/one"),
                List.of("kubectl", "apply", "-f", "deploy/two")), commands);
        assertEquals(2, results.size());
    }

    @Test
    void deployStopsAtTheFirstFailingEntry() throws Exception {
        ApproachDescriptor wayA = descriptor("way-a");
        ApproachDescriptor two = new ApproachDescriptor(wayA.name(), wayA.title(), List.of("deploy/one", "deploy/two"),
                wayA.reset(), wayA.commit(), null, null, wayA.infrastructure(), wayA.declared());
        exitCode = 1;

        List<ChangeTrigger.Result> results = new CommandAdapter(two, Fixtures.protocol(), fake).deploy(clock);

        assertEquals(List.of(List.of("kubectl", "apply", "-f", "deploy/one")), commands);
        assertEquals(1, results.get(0).exitCode());
    }

    @Test
    void triggerAndSourceCheckAreEmptyWhenTheDescriptorHasNone() throws Exception {
        CommandAdapter adapter = adapter("way-a");

        assertEquals(Optional.empty(), adapter.trigger(new Payload("v2", "1000"), clock));
        assertEquals(Optional.empty(), adapter.sourceCheck(clock));
        assertEquals(List.of(), commands);
    }

    @Test
    void triggerAndSourceCheckRunTheirCommands() throws Exception {
        CommandAdapter adapter = adapter("way-c");

        Optional<ChangeTrigger.Result> trigger = adapter.trigger(new Payload("v2", "1000"), clock);
        Optional<ChangeTrigger.Result> check = adapter.sourceCheck(clock);

        assertEquals(List.of(descriptor("way-c").trigger(), descriptor("way-c").sourceCheck()), commands);
        assertEquals(0, trigger.orElseThrow().exitCode());
        assertEquals(25, check.orElseThrow().finishedAt());
    }

    @Test
    void nameInfrastructureAndDeclaredComeFromTheDescriptor() throws Exception {
        CommandAdapter adapter = adapter("way-c");

        assertEquals("way-c", adapter.name());
        assertEquals(descriptor("way-c").infrastructure(), adapter.infrastructure());
        assertEquals(descriptor("way-c").declared(), adapter.declared());
    }

    private CommandAdapter adapter(String name) throws Exception {
        return new CommandAdapter(descriptor(name), Fixtures.protocol(), fake);
    }

    private static ApproachDescriptor descriptor(String name) throws Exception {
        return ApproachDescriptor.read(Fixtures.ROOT.resolve("approaches/" + name + "/approach.json"));
    }
}
