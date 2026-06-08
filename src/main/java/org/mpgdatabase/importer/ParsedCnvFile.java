package org.mpgdatabase.importer;

import java.util.List;
import java.util.Map;

public record ParsedCnvFile(Map<String, String> metadata, String callingMethod, List<CnvRecord> records) {
}
