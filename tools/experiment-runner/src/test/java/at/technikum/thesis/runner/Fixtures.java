package at.technikum.thesis.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.util.function.Consumer;

final class Fixtures {

    static final Path ROOT = Path.of("../..");
    static final Path PROTOCOL = ROOT.resolve("protocol/protocol-v1.json");
    static final ObjectMapper JSON = new ObjectMapper();

    private Fixtures() {
    }

    static Protocol protocol() throws Exception {
        return Protocol.read(PROTOCOL);
    }

    static Protocol protocolWith(Consumer<ObjectNode> change) throws Exception {
        return changed(PROTOCOL, Protocol.class, change);
    }

    static <T> T changed(Path file, Class<T> type, Consumer<ObjectNode> change) throws Exception {
        ObjectNode tree = (ObjectNode) JSON.readTree(file.toFile());
        change.accept(tree);
        return JSON.treeToValue(tree, type);
    }

    static Observation observation(long seq, double intendedAt, double receivedAt) {
        return new Observation(seq, intendedAt, intendedAt, intendedAt, receivedAt, 200, "pod-a", "v1", 0, null);
    }
}
