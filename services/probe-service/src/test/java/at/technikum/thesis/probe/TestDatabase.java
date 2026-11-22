package at.technikum.thesis.probe;

import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

final class TestDatabase {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.2");

    static {
        POSTGRES.start();
    }

    private TestDatabase() {
    }

    static PostgreSQLContainer container() {
        return POSTGRES;
    }

    static Connection connect() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    static int rowsWithVersion(String configVersion) {
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(
                     "select count(*) from probe_request where config_version = ?")) {
            statement.setString(1, configVersion);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
