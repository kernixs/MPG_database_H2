package org.mpgdatabase.importer;

import java.nio.file.Path;

public class CnvParserFactory {
    private final String manualCallingMethod;

    public CnvParserFactory() {
        this(null);
    }

    public CnvParserFactory(String manualCallingMethod) {
        this.manualCallingMethod = manualCallingMethod;
    }

    public CnvParser parserFor(Path path) {
        return new TsvCnvParser(manualCallingMethod);
    }
}
