package org.mpgdatabase.importer;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public interface CnvParser {
    ParsedCnvFile parse(Path path) throws IOException;
}
