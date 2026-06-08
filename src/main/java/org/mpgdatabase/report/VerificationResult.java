package org.mpgdatabase.report;

import java.util.ArrayList;
import java.util.List;

public class VerificationResult {
    private final String name;
    private boolean passed = true;
    private final List<String> messages = new ArrayList<>();

    public VerificationResult(String name) {
        this.name = name;
    }

    public String name() {
        return name;
    }

    public boolean passed() {
        return passed;
    }

    public List<String> messages() {
        return List.copyOf(messages);
    }

    public void fail(String message) {
        passed = false;
        messages.add(message);
    }

    public void pass(String message) {
        messages.add(message);
    }
}
