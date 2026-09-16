package org.stypox.dicio.smarttubeplayauto

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class SmartTubeTargetSelectionTest : StringSpec({
    fun installed(
        pkg: String,
        evidence: Set<String> = setOf(SmartTubeEvidence.DEVICE_INSTALLED),
    ) = SmartTubeInstalledPackage(
        packageName = pkg,
        installed = true,
        applicationLabel = "SmartTube",
        evidence = evidence,
        source = "test",
    )

    "harness package is never a SmartTube target" {
        SmartTubeHarnessIdentity.isHarness(SmartTubeHarnessIdentity.PACKAGE) shouldBe true
        SmartTubeHarnessIdentity.isHarness("${SmartTubeHarnessIdentity.PACKAGE}.debug") shouldBe true
        SmartTubePackageNames.looksLikeSmartTube(SmartTubeHarnessIdentity.PACKAGE) shouldBe false
        SmartTubePackageNames.isSafeSmartTubeTarget(SmartTubeHarnessIdentity.PACKAGE) shouldBe false
        SmartTubeTargetSelection.selectable(
            listOf(installed(SmartTubeHarnessIdentity.PACKAGE)),
        ).shouldBeEmpty()
        SmartTubeTargetSelection.pin(
            listOf(installed(SmartTubeHarnessIdentity.PACKAGE)),
            explicitPackage = null,
        ) shouldBe SmartTubePinDecision.None
        SmartTubeTargetSelection.pin(
            listOf(installed(SmartTubeCatalog.ORG_SMARTTUBE_BETA)),
            explicitPackage = SmartTubeHarnessIdentity.PACKAGE,
        ) shouldBe SmartTubePinDecision.Rejected("harness_package_forbidden")
    }

    "catalog-only not-installed rows are not selectable" {
        val catalog = SmartTubeInstalledPackage(
            packageName = SmartTubeCatalog.TEAMSMART,
            installed = false,
            evidence = setOf(SmartTubeEvidence.CATALOG_CANDIDATE),
            source = "catalog",
        )
        SmartTubeTargetSelection.selectable(listOf(catalog)).shouldBeEmpty()
        SmartTubeTargetSelection.pin(listOf(catalog), null) shouldBe SmartTubePinDecision.None
    }

    "single DEVICE_INSTALLED org.smarttube package is selected" {
        val beta = installed(SmartTubeCatalog.ORG_SMARTTUBE_BETA)
        val decision = SmartTubeTargetSelection.pin(listOf(beta), null)
        decision.shouldBeInstanceOf<SmartTubePinDecision.Selected>()
            .packageName shouldBe SmartTubeCatalog.ORG_SMARTTUBE_BETA
    }

    "beta and stable together require explicit selection" {
        val packages = listOf(
            installed(SmartTubeCatalog.ORG_SMARTTUBE_BETA),
            installed(SmartTubeCatalog.ORG_SMARTTUBE_STABLE),
        )
        val ambiguous = SmartTubeTargetSelection.pin(packages, null)
        ambiguous.shouldBeInstanceOf<SmartTubePinDecision.Ambiguous>()
            .packages shouldBe listOf(
                SmartTubeCatalog.ORG_SMARTTUBE_BETA,
                SmartTubeCatalog.ORG_SMARTTUBE_STABLE,
            )
        SmartTubeTargetSelection.pin(
            packages,
            SmartTubeCatalog.ORG_SMARTTUBE_STABLE,
        ).shouldBeInstanceOf<SmartTubePinDecision.Selected>()
            .packageName shouldBe SmartTubeCatalog.ORG_SMARTTUBE_STABLE
    }

    "YouTube is never selectable" {
        SmartTubePackageNames.isSafeSmartTubeTarget("com.google.android.youtube") shouldBe false
        SmartTubeTargetSelection.pin(
            listOf(installed(SmartTubeCatalog.ORG_SMARTTUBE_BETA)),
            explicitPackage = "com.google.android.youtube",
        ) shouldBe SmartTubePinDecision.Rejected("youtube_package_forbidden")
    }

    "label matcher requires SmartTube, not the harness app name alone" {
        SmartTubePackageNames.labelMatchesSmartTube("SmartTube") shouldBe true
        SmartTubePackageNames.labelMatchesSmartTube("Smart Tube Beta") shouldBe true
        SmartTubePackageNames.labelMatchesSmartTube("YouTube") shouldBe false
        SmartTubePackageNames.labelMatchesSmartTube("CARFU SmartTube P1.1") shouldBe true
        SmartTubePackageNames.isSafeSmartTubeTarget(SmartTubeHarnessIdentity.PACKAGE) shouldBe false
    }
})
