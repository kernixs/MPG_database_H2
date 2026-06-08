package org.mpgdatabase.importer;

public record ImportResult(String fileName, boolean success, int recordsSeen, int segmentsInserted, int issuesInserted) {
}
