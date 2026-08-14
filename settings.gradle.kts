rootProject.name = "plotted"

include("plotted-api")

// The CP-SAT optimiser, in its own JVM. OR-Tools is a JNI binding, and a native
// crash kills the process rather than raising an exception -- so keeping it out
// of the API's process is what stops one bad solve taking every endpoint with
// it. See docs/adr/0010-optimiser-runs-in-its-own-process.md.
include("plotted-solver")

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
