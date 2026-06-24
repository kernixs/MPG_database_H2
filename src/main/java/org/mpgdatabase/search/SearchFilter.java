package org.mpgdatabase.search;

public record SearchFilter(String name, String value) {
    public boolean hasValue() {
        return value != null && !value.isBlank();
    }
}
