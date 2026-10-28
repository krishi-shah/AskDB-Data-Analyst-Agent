package ca.yorku.askdb.agent;

import java.util.List;

public record VerificationReport(boolean verified, List<String> checked, List<String> unverified) {

    public String feedback() {
        return "These numbers do not appear in the result or follow from it: "
                + String.join(", ", unverified) + ". Use only numbers from the result.";
    }
}
