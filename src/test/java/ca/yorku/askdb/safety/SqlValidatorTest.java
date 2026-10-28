package ca.yorku.askdb.safety;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SqlValidatorTest {
    private final SqlValidator validator = new SqlValidator(50);

    @Test
    void selectPassesAndGetsRowLimit() {
        ValidationResult result = validator.validate("SELECT region FROM customers;");
        assertTrue(result.ok());
        assertEquals("SELECT * FROM (SELECT region FROM customers) LIMIT 50", result.sql());
    }

    @Test
    void withQueryPasses() {
        assertTrue(validator.validate("WITH t AS (SELECT 1 AS x) SELECT x FROM t").ok());
    }

    @Test
    void multipleStatementsRejected() {
        ValidationResult result = validator.validate("SELECT 1; DROP TABLE orders");
        assertFalse(result.ok());
        assertEquals("single_statement", result.rule());
    }

    @ParameterizedTest
    @ValueSource(strings = {"DELETE FROM orders", "UPDATE orders SET total = 0", "DROP TABLE orders",
            "INSERT INTO orders VALUES (1)", "WITH t AS (SELECT 1) DELETE FROM orders"})
    void writeStatementsRejected(String sql) {
        ValidationResult result = validator.validate(sql);
        assertFalse(result.ok());
        assertEquals("read_only", result.rule());
    }

    @ParameterizedTest
    @ValueSource(strings = {"SELECT * FROM sqlite_master", "SELECT load_extension('x')"})
    void forbiddenObjectsRejected(String sql) {
        ValidationResult result = validator.validate(sql);
        assertFalse(result.ok());
        assertEquals("forbidden_object", result.rule());
    }

    @Test
    void keywordsInsideLiteralsAreIgnored() {
        assertTrue(validator.validate("SELECT 'drop; delete' AS note").ok());
        assertTrue(validator.validate("SELECT replace(name, 'a', 'b') FROM customers").ok());
    }

    @Test
    void emptySqlRejected() {
        assertFalse(validator.validate("  ;  ").ok());
    }
}
