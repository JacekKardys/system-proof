package io.github.jacekkardys.systemproof.examples.postgres;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class DatabaseOperations {
    private final String jdbcUrl;
    private final String username;
    private final String password;

    DatabaseOperations(String jdbcUrl, String username, String password) {
        this.jdbcUrl = Objects.requireNonNull(jdbcUrl, "jdbcUrl must not be null");
        this.username = Objects.requireNonNull(username, "username must not be null");
        this.password = Objects.requireNonNull(password, "password must not be null");
    }

    void initialize() {
        String sql = """
            CREATE TABLE example_entry (
                id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                value VARCHAR(255) NOT NULL
            )
            """;
        try (Connection connection = connect();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException exception) {
            throw new IllegalStateException("Cannot initialize example schema", exception);
        }
    }

    void insert(String value) {
        Objects.requireNonNull(value, "value must not be null");
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(
                 "INSERT INTO example_entry (value) VALUES (?)"
             )) {
            statement.setString(1, value);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Cannot insert example value", exception);
        }
    }

    List<String> values() {
        List<String> values = new ArrayList<>();
        try (Connection connection = connect();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                 "SELECT value FROM example_entry ORDER BY id"
             )) {
            while (result.next()) {
                values.add(result.getString("value"));
            }
            return List.copyOf(values);
        } catch (SQLException exception) {
            throw new IllegalStateException("Cannot read example values", exception);
        }
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, username, password);
    }
}
