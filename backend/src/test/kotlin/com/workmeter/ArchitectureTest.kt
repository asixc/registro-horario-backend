package com.workmeter

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.junit.jupiter.api.Test

class ArchitectureTest {
    @Test fun `core depends only on Kotlin JDK and inner layers`() {
        val classes = ClassFileImporter().importPackages("com.workmeter")
        noClasses().that().resideInAnyPackage("com.workmeter.domain..", "com.workmeter.application..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "jakarta..", "io.quarkus..", "org.hibernate..", "com.fasterxml..",
                "com.workmeter.infrastructure..").check(classes)
        noClasses().that().resideInAPackage("com.workmeter.domain..")
            .should().dependOnClassesThat().resideInAPackage("com.workmeter.application..").check(classes)
    }
}
