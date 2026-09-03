package com.youkeda.exercise.claw;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.*;

/**
 * 架构约束测试 — 确保模块间的依赖关系符合架构设计。
 *
 * <p>如果新增了跨包依赖导致测试失败，先问自己：
 * 这个依赖符合层次规则吗？如果确实需要，在 {@link #ALLOWED_VIOLATIONS} 中明确注释原因。
 */
class ArchitectureTest {

    private static JavaClasses classes;

    /** 已知的、经过确认的违规（用 ArchUnit 的 {@code because()} 说明原因） */
    private static final String ARCH_PACKAGE = "com.youkeda.exercise.claw..";

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.youkeda.exercise.claw");
    }

    // ========== 层级一：domain 必须纯净 ==========

    @Test
    void domainMustNotDependOnOtherLayers() {
        noClasses()
                .that().resideInAPackage("..domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..agent..",
                        "..feature..",
                        "..tool..",
                        "..skill..",
                        "..ai..",
                        "..infrastructure..",
                        "..notification..",
                        "..application..")
                .because("domain/ 是纯净领域模型，禁止依赖任何其他层")
                .check(classes);
    }

    // ========== 层级二：agent 不能依赖 feature ==========

    @Test
    void agentMustNotDependOnFeature() {
        noClasses()
                .that().resideInAPackage("..agent..")
                .should().dependOnClassesThat().resideInAPackage("..feature..")
                .because("agent/ 是内核运行时，不应直接依赖具体的业务功能实现")
                .check(classes);
    }

    // ========== 层级四：tool 中的类必须实现 Tool 接口（或明确声明例外） ==========

    @Test
    void everyClassInToolPackageShouldImplementTool() {
        classes()
                .that().resideInAPackage("com.youkeda.exercise.claw.tool..")
                .and().areNotEnums()
                .and().areNotInterfaces()
                .and().areNotMemberClasses()
                .and().areNotAnonymousClasses()
                .should().implement(com.youkeda.exercise.claw.agent.runtime.Tool.class)
                .because("tool/ 包中的主类应当实现 Tool 接口")
                .check(classes);
    }
}
