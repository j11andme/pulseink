package com.pulseink.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

class ArchitectureRulesTest {

    @Test
    void domainAndAgentMustNotDependOnFrameworkAdapters() {
        var classes = new ClassFileImporter().importPackages("com.pulseink");

        noClasses().that().resideInAnyPackage("..domain..", "..agent..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "org.springframework..",
                        "com.baomidou..",
                        "co.elastic..",
                        "org.apache.kafka..",
                        "org.apache.tika..")
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    void controllersMustNotDependOnConcreteClientsOrRepositories() {
        var classes = new ClassFileImporter().importPackages("com.pulseink");

        noClasses().that().resideInAPackage("..controller..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("..client..", "..repository..")
                .allowEmptyShould(true)
                .check(classes);
    }
}
