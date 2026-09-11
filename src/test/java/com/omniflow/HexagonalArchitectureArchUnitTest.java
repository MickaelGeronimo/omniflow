package com.omniflow;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

@AnalyzeClasses(packages = "com.omniflow", importOptions = ImportOption.DoNotIncludeTests.class)
public class HexagonalArchitectureArchUnitTest {

    @ArchTest
    public static final ArchRule hexagonal_architecture_layers_are_strictly_respected =
            layeredArchitecture()
                    .consideringOnlyDependenciesInAnyPackage("com.omniflow..")
                    .layer("Domain").definedBy("com.omniflow.domain..")
                    .layer("Application").definedBy("com.omniflow.application..")
                    .layer("Infrastructure").definedBy("com.omniflow.infrastructure..")
                    .whereLayer("Domain").mayNotAccessAnyLayer()
                    .whereLayer("Application").mayOnlyAccessLayers("Domain")
                    .whereLayer("Infrastructure").mayOnlyAccessLayers("Application", "Domain");
}
