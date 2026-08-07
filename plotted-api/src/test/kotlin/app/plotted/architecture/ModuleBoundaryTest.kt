package app.plotted.architecture

import com.tngtech.archunit.base.DescribedPredicate.alwaysTrue
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.lang.ArchCondition
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.ConditionEvents
import com.tngtech.archunit.lang.SimpleConditionEvent
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices

/**
 * Plotted is a modular monolith, which is only true if something checks. These
 * rules are the check: they fail the build on a cross-module package dependency,
 * which is what keeps "modular monolith" from decaying into "one big package"
 * without anyone deciding to let it (spec section 13).
 *
 * Splitting into services would enforce the same boundaries at the cost of a
 * network hop, deployment topology and distributed failure modes that the
 * expected scale does not justify.
 */
@AnalyzeClasses(
    packages = ["app.plotted"],
    importOptions = [ImportOption.DoNotIncludeTests::class],
)
class ModuleBoundaryTest {
    /**
     * Feature modules do not reference each other. `platform` is the shared
     * kernel and `generated` is jOOQ output, so both are legitimate dependencies
     * for anyone.
     */
    @ArchTest
    val featureModulesAreIndependent: ArchRule =
        slices()
            .matching("app.plotted.(*)..")
            .namingSlices("\$1")
            .`as`("Feature modules")
            .should().notDependOnEachOther()
            .ignoreDependency(
                alwaysTrue(),
                resideInAnyPackage("app.plotted.platform..", "app.plotted.generated.."),
            )

    @ArchTest
    val moduleGraphIsAcyclic: ArchRule =
        slices()
            .matching("app.plotted.(*)..")
            .namingSlices("\$1")
            .`as`("Feature modules")
            .should().beFreeOfCycles()

    /**
     * The shared kernel stays shared. If `platform` starts depending on a feature
     * module it is no longer a kernel, it is a second copy of the application.
     */
    @ArchTest
    val platformDependsOnNoFeatureModule: ArchRule =
        noClasses()
            .that().resideInAPackage("app.plotted.platform..")
            .should().dependOnClassesThat(
                resideInAnyPackage(
                    "app.plotted.identity..",
                    "app.plotted.demo..",
                    "app.plotted.catalogue..",
                    "app.plotted.availability..",
                    "app.plotted.watchlist..",
                    "app.plotted.viewing..",
                    "app.plotted.recommendation..",
                    "app.plotted.optimisation..",
                    "app.plotted.preferences..",
                    "app.plotted.subscriptions..",
                    "app.plotted.households..",
                    "app.plotted.notifications..",
                    "app.plotted.analytics..",
                    "app.plotted.alerts..",
                ),
            )

    /**
     * Controllers go through a domain service. A controller that reaches straight
     * into a repository is where authorisation checks get skipped.
     */
    @ArchTest
    val controllersDoNotReachIntoPersistence: ArchRule =
        noClasses()
            .that().resideInAPackage("..api..")
            .should().dependOnClassesThat().resideInAPackage("..persistence..")

    /** The domain does not know it is being served over HTTP. */
    @ArchTest
    val domainDoesNotDependOnTheApiLayer: ArchRule =
        noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAPackage("..api..")

    /**
     * SQL lives in persistence packages. This is the rule that keeps a query
     * from being written inline in a controller "just this once".
     *
     * Scoped by package rather than by class name deliberately. A Kotlin lambda
     * inside a repository compiles to a synthetic class whose name does not end
     * in `Repository`, so the name-based version of this rule reported a
     * repository's own internals as violations. The package is what actually
     * expresses the intent.
     */
    @ArchTest
    val sqlIsConfinedToPersistencePackages: ArchRule =
        noClasses()
            .that().resideOutsideOfPackages("..persistence..", "app.plotted.generated..")
            .should().dependOnClassesThat().haveFullyQualifiedName("org.jooq.DSLContext")

    /**
     * Generated table constants are an implementation detail of persistence. A
     * service importing them means the schema has leaked into the domain.
     */
    @ArchTest
    val generatedCodeIsUsedOnlyInPersistencePackages: ArchRule =
        noClasses()
            .that().resideOutsideOfPackages("..persistence..", "app.plotted.generated..")
            .should().dependOnClassesThat().resideInAPackage("app.plotted.generated..")

    /** ...and the naming stays honest in the other direction too. */
    @ArchTest
    val repositoriesLiveInPersistencePackages: ArchRule =
        classes()
            .that().haveSimpleNameEndingWith("Repository")
            .and().resideOutsideOfPackage("app.plotted.generated..")
            .should().resideInAPackage("..persistence..")

    /**
     * No two API classes share a simple name.
     *
     * springdoc keys `components.schemas` by simple class name, so two DTOs
     * called the same thing in different packages silently overwrite each other
     * in the generated document. Nothing throws, the document generates, and the
     * Angular client is then wrong for whichever endpoint lost — it gets fields
     * that do not exist and misses fields that do.
     *
     * Found the hard way in phase 5: `optimisation.api.CoveredTitleResponse` and
     * `watchlist.api.CoveredTitleResponse` produced a specification in which the
     * optimiser's covered titles carried a `priority` and no `month`. The drift
     * check could not catch it, because the drifted document was internally
     * consistent and matched itself perfectly.
     *
     * Restricted to top-level classes for the same reason
     * [sqlIsConfinedToPersistencePackages] is scoped by package: every Kotlin
     * companion object compiles to a nested class called `Companion`, and an
     * inline `sortedBy` compiles to an anonymous one with no name at all.
     * Neither can ever appear in `components.schemas`, and counting them makes
     * the rule fail on every file that has a companion — which is all of them.
     */
    @ArchTest
    val apiClassNamesAreUnique: ArchRule =
        classes()
            .that().resideInAPackage("..api..")
            .and().areTopLevelClasses()
            .should(haveASimpleNameNoOtherApiClassUses())

    private companion object {
        fun haveASimpleNameNoOtherApiClassUses(): ArchCondition<JavaClass> =
            object : ArchCondition<JavaClass>("have a simple name no other API class uses") {
                private var shared: Set<String> = emptySet()

                override fun init(allClasses: MutableCollection<out JavaClass>) {
                    shared = allClasses.groupBy { it.simpleName }.filterValues { it.size > 1 }.keys
                }

                override fun check(item: JavaClass, events: ConditionEvents) {
                    if (item.simpleName in shared) {
                        events.add(
                            SimpleConditionEvent.violated(
                                item,
                                "${item.fullName} shares the simple name '${item.simpleName}' with another API " +
                                    "class, so they collide in components.schemas",
                            ),
                        )
                    }
                }
            }
    }
}
