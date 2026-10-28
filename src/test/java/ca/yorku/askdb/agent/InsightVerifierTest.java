package ca.yorku.askdb.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.yorku.askdb.data.QueryResult;
import java.util.List;
import org.junit.jupiter.api.Test;

class InsightVerifierTest {
    private final InsightVerifier verifier = new InsightVerifier();
    private final QueryResult result = new QueryResult(List.of("region", "revenue"),
            List.of(List.of("North", 600.0), List.of("South", 300.0), List.of("West", 100.0)), false, 0, "");

    @Test
    void cellValuesAndRoundingAreVerified() {
        assertTrue(verifier.verify(new Insight("North leads with $600 and West has 100.0."), result, "").verified());
    }

    @Test
    void derivedValuesAreVerified() {
        String text = "The total is 1,000, North holds 60% of it, and the average is 333.33.";
        assertTrue(verifier.verify(new Insight(text), result, "").verified());
    }

    @Test
    void percentageChangeIsVerified() {
        assertTrue(verifier.verify(new Insight("South is 50% below North."), result, "").verified());
    }

    @Test
    void inventedNumberIsFlagged() {
        VerificationReport report = verifier.verify(new Insight("North leads with 750."), result, "");
        assertFalse(report.verified());
        assertEquals(List.of("750"), report.unverified());
    }

    @Test
    void numbersFromQuestionAreAllowed() {
        VerificationReport report = verifier.verify(new Insight("Across the top 3 regions in 2025, North leads."),
                result, "top 3 regions in 2025");
        assertTrue(report.verified());
    }
}
