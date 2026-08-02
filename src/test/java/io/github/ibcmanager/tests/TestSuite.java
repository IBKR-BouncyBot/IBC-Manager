package io.github.ibcmanager.tests;

import java.util.List;

public interface TestSuite {
    String name();
    List<NamedTest> tests();
}
