package app.plotted.architecture

import com.tngtech.archunit.base.DescribedPredicate.alwaysTrue
import com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.lang.ArchRule
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
                    "app.plotted.catalogue..",
                    "app.plotted.availability..",
                    "app.plotted.watchlist..",
                    "app.plotted.viewing..",
                    "app.plotted.recommendation..",
                    "app.plotted.optimisation..",
                    "app.plotted.subscriptions..",
                    "app.plotted.households..",
                    "app.plotted.notifications..",
                    "app.plotted.analytics..",
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
     * SQL lives in repositories. This is the rule that keeps a query from being
     * written inline in a controller "just this once".
     */
    @ArchTest
    val sqlIsConfinedToRepositories: ArchRule =
        noClasses()
            .that().resideOutsideOfPackage("app.plotted.generated..")
            .and().haveSimpleNameNotEndingWith("Repository")
            .should().dependOnClassesThat().haveFullyQualifiedName("org.jooq.DSLContext")

    /**
     * Generated table constants are an implementation detail of persistence. A
     * service importing them means the schema has leaked into the domain.
     */
    @ArchTest
    val generatedCodeIsUsedOnlyByRepositories: ArchRule =
        noClasses()
            .that().resideOutsideOfPackage("app.plotted.generated..")
            .and().haveSimpleNameNotEndingWith("Repository")
            .should().dependOnClassesThat().resideInAPackage("app.plotted.generated..")
}
