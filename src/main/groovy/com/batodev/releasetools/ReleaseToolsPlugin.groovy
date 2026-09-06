package com.batodev.releasetools

import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.DetektPlugin
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.process.ExecOperations
import org.jlleitschuh.gradle.ktlint.KtlintPlugin

import javax.inject.Inject

/**
 * Shared release-automation tasks for the game projects in this workspace.
 * Consumed as an included build via `pluginManagement { includeBuild(...) }`
 * in each project's settings.gradle - no publishing step required.
 */
class ReleaseToolsPlugin implements Plugin<Project> {

    private final ExecOperations execOperations

    @Inject
    ReleaseToolsPlugin(ExecOperations execOperations) {
        this.execOperations = execOperations
    }

    @Override
    void apply(Project project) {
        relocateBuildDirs(project)

        def extension = project.extensions.create("releaseTools", ReleaseToolsExtension)
        extension.publishTaskName.convention("publishReleaseBundle")
        extension.promoteTaskName.convention("promoteReleaseArtifact")

        File versionPropsFile = project.file("version.properties")

        // versionCode/versionName are read from version.properties by the consuming
        // app/build.gradle's `android { defaultConfig { ... } }` block, which is
        // configured *after* this plugin's apply() runs (the plugins {} block resolves
        // before later script statements execute). Bumping the file here - during
        // configuration, not as a task action - means a single invocation of
        // createNewInternalTestVersion builds and publishes the newly bumped version
        // instead of the pre-bump one (Gradle can't reconfigure mid-run, so editing the
        // file from a task *action* would only take effect on the *next* invocation).
        if (wasRequested(project, "createNewInternalTestVersion")) {
            bumpVersion(versionPropsFile)
        }

        File rootDir = project.rootDir

        project.tasks.register("createNewInternalTestVersion") { task ->
            task.group = "publishing"
            task.description = "Bumps versionCode/versionName in version.properties, then builds and publishes to the internal testing track."
            task.dependsOn(extension.publishTaskName)
            // doLast only runs once publishReleaseBundle (a dependency) has actually
            // succeeded - if the build fails partway (e.g. a compile error), Gradle
            // aborts before this runs, so a failed release leaves an uncommitted bump
            // on disk instead of a commit falsely recording a published release.
            //
            // Captures rootDir (a File) rather than project itself - the config
            // cache can't serialize a Project reference captured in a task action.
            task.doLast {
                commitVersionBump(execOperations, rootDir, versionPropsFile)
            }
        }

        project.tasks.register("promoteInternalToProd", PromoteInternalToProdTask) { task ->
            task.group = "publishing"
            task.description = "Promotes the current internal testing release to production."
            task.promoteTaskName.set(extension.promoteTaskName)
        }

        // Applied here instead of via `id` in every consuming build.gradle - it
        // ships on this plugin's own classpath (see release-tools/build.gradle).
        project.pluginManager.apply('com.github.ben-manes.versions')

        restrictDependencyUpdatesToStable(project)

        // Every consuming app declared its own byte-identical `id("com.github.triplet.play")`
        // + `play { ... }` block (same credentials file, same "internal" track). Centralized
        // the same way as ben-manes.versions above: applied programmatically from this
        // plugin's own classpath, configured once here instead of 27+ times.
        project.pluginManager.apply('com.github.triplet.play')
        configurePlayPublishing(project)

        // Same centralization for copy/paste detection: applied and configured once
        // here instead of every repo hand-rolling its own PMD CLI invocation (or, worse,
        // 27+ copies drifting out of sync on threshold/language).
        project.pluginManager.apply('org.danilopianini.cpd')
        configureCpd(project)
        wireCheckDependency(project, 'cpdCheck')

        // ktlint formatting and detekt static analysis, fleet-wide. Wired from here
        // (rather than an `id` in every module's build.gradle - detekt's own `id` used
        // to be declared individually in each of antimine_with_pics_as_prizes' 19
        // modules, for example) because this plugin is only ever applied to a repo's
        // main app module, not every subproject (confirmed: `com.batodev.releasetools`
        // appears in exactly one build.gradle per repo). Walking rootProject.subprojects
        // here and registering a deferred `withPlugin` listener - rather than requiring
        // this plugin to already be applied - is what reaches every Kotlin module
        // regardless of Gradle's subproject configuration order.
        wireKtlintFleetWide(project)
        wireDetektFleetWide(project)

        // AGP already wires `lint` into a module's own `check` by default, but this
        // makes it explicit fleet-wide rather than relying on AGP's default holding -
        // and it's the only one of the four verification tasks (lint/detekt/cpdCheck/
        // ktlintCheck) not already wired above/below, so `./gradlew check` runs all
        // four uniformly everywhere they apply.
        wireLintFleetWide(project)
    }

    // Listened for by plugin id, not just 'org.jetbrains.kotlin.android'/'org.jetbrains.kotlin.jvm'
    // - AGP 9's built-in Kotlin support (the 'android.builtInKotlin' default flipped to true,
    // see AGP's own deprecation warning for the old opt-out) means several repos' Android
    // modules (e.g. arrows_game's :app and its android-library convention plugin) never apply
    // a separate kotlin-android plugin id at all, compiling Kotlin via AGP itself instead -
    // 'org.jetbrains.kotlin.android' alone silently missed those modules entirely.
    private static final List<String> KOTLIN_BEARING_PLUGIN_IDS = [
            'com.android.application',
            'com.android.library',
            'org.jetbrains.kotlin.android',
            'org.jetbrains.kotlin.jvm',
    ]

    private static void wireKtlintFleetWide(Project project) {
        project.rootProject.subprojects.each { subproject ->
            KOTLIN_BEARING_PLUGIN_IDS.each { pluginId ->
                subproject.pluginManager.withPlugin(pluginId) {
                    applyKtlint(subproject)
                }
            }
        }
    }

    private static void applyKtlint(Project subproject) {
        // A module can match more than one id above (e.g. com.android.library +
        // org.jetbrains.kotlin.android both applied) - each firing its own withPlugin
        // listener - so guard against configuring the same subproject twice.
        if (subproject.plugins.hasPlugin(KtlintPlugin)) {
            return
        }

        // Applied by class reference, not by string id ('subproject.pluginManager.apply(<id>)')
        // - id-based lookup resolves against *that specific subproject's own* buildscript
        // classpath, which only ever includes the ktlint plugin for the one module that
        // itself declares `id 'com.batodev.releasetools'` (real failure observed on
        // arrows_game's `domain` module and antimine_with_pics_as_prizes' 19 modules:
        // "Plugin with id 'org.jlleitschuh.gradle.ktlint' not found", silently producing
        // zero changes rather than an error visible in this method's own output). Class-based
        // apply() instantiates the already-loaded KtlintPlugin class directly - it's on
        // *this* plugin's own classpath (declared in release-tools/build.gradle) - so it
        // reaches every subproject regardless of that subproject's own classpath.
        subproject.pluginManager.apply(KtlintPlugin)

        VersionCatalogsExtension catalogs = subproject.rootProject.extensions.getByType(VersionCatalogsExtension)
        def libs = catalogs.named('libs')
        String ktlintEngineVersion = libs.findVersion('ktlintEngine').get().requiredVersion
        String composeRulesCoordinate = libs.findLibrary('nlopez-compose-rules-ktlint').get().get()

        subproject.extensions.configure('ktlint') { ext ->
            ext.version.set(ktlintEngineVersion)
            // Android Kotlin style guide (4-space continuation indent etc.) for
            // modules AGP actually applies to; plain Kotlin modules (e.g. a
            // libGDX `core`) get ktlint's own default style instead.
            boolean isAndroidModule = subproject.pluginManager.hasPlugin('com.android.application') ||
                    subproject.pluginManager.hasPlugin('com.android.library')
            ext.android.set(isAndroidModule)

            // ktlint_function_naming_ignore_when_annotated_with: without this,
            // ktlint's standard function-naming rule (which predates Compose and
            // knows nothing about it) flags every @Composable function that emits UI
            // for using PascalCase - the correct, conventional name style for those
            // functions, not a violation. This is ktlint's own documented fix for
            // Compose codebases, not a project-specific workaround.
            //
            // compose_allowed_composition_locals: theme data exposed via a static
            // CompositionLocal (the very pattern MaterialTheme itself uses) is an
            // intentional, idiomatic design choice, not the CompositionLocal overuse
            // the compose-rules allowlist check guards against. Allowlisting the
            // fleet's theme locals keeps that check active for genuinely ad-hoc ones.
            ext.additionalEditorconfig.set([
                    'ktlint_function_naming_ignore_when_annotated_with': 'Composable',
                    'compose_allowed_composition_locals'               : 'LocalThemeColors,LocalBoardColors',
            ])

            // Never lint generated Kotlin (e.g. BuildConfig.kt under
            // build/generated/...): we neither author it nor can fix its style - its
            // formatting is the generator's, not ours. CPD already skips generated
            // sources (via the task deps in configureCpd below); mirror that for
            // ktlint by dropping any file under a 'generated' output dir.
            ext.filter { exclude { element ->
                String p = element.file.absolutePath.replace(File.separator, '/')
                // generated sources (BuildConfig, etc.) - never ours to author/fix
                p.contains('/generated/') ||
                    // vendored third-party code, owned/styled upstream not here - the
                    // same files are excluded from CPD in configureCpd below (QQWing;
                    // Chris Boyle's sgtpuzzles port under name.boyle.chris)
                    p.contains('/core/qqwing/') ||
                    p.contains('/name/boyle/chris/')
            } }
        }

        // compose-rules is Compose-aware: it only fires on files that actually
        // use Compose APIs, so adding it to every Kotlin module (not just the
        // ones using Compose today) is a no-op for the rest rather than noise.
        subproject.dependencies.add('ktlintRuleset', composeRulesCoordinate)

        wireCheckDependency(subproject, 'ktlintCheck')
    }

    private static void wireDetektFleetWide(Project project) {
        project.rootProject.subprojects.each { subproject ->
            KOTLIN_BEARING_PLUGIN_IDS.each { pluginId ->
                subproject.pluginManager.withPlugin(pluginId) {
                    applyDetekt(subproject)
                }
            }
        }
    }

    private static void applyDetekt(Project subproject) {
        // Same double-apply guard as applyKtlint above - a module can match more
        // than one KOTLIN_BEARING_PLUGIN_IDS entry.
        if (subproject.plugins.hasPlugin(DetektPlugin)) {
            return
        }

        // Applied by class reference rather than by string id, for the same reason
        // applyKtlint above does - id-based lookup only resolves against a
        // subproject's *own* buildscript classpath, which doesn't include detekt
        // unless that specific subproject also declares `id("com.batodev.releasetools")`.
        subproject.pluginManager.apply(DetektPlugin)

        VersionCatalogsExtension catalogs = subproject.rootProject.extensions.getByType(VersionCatalogsExtension)
        def libs = catalogs.named('libs')
        String detektVersion = libs.findVersion('detekt').get().requiredVersion

        // config/detekt/detekt.yml lives alongside this plugin's own sources, same
        // relative-path technique pmdVersion() below uses to read libs.versions.toml -
        // resolved against the *consuming* project's rootDir, not this plugin's.
        File configFile = new File(subproject.rootProject.projectDir, "../release-tools/config/detekt/detekt.yml")

        subproject.extensions.configure('detekt') { ext ->
            ext.toolVersion = detektVersion
            // Start from detekt's own curated defaults and only apply this file's
            // overrides on top, rather than requiring it to restate every rule from
            // scratch - matches how the fleet's ktlint config only lists overrides too.
            ext.buildUponDefaultConfig = true
            ext.config.setFrom(configFile)
        }

        subproject.tasks.withType(Detekt).configureEach { task ->
            // Same generated/vendored exclusions as applyKtlint's ext.filter above -
            // neither is ours to author or fix the style/complexity of.
            task.exclude '**/generated/**'
            task.exclude '**/core/qqwing/**'
            task.exclude '**/name/boyle/chris/**'
        }

        wireCheckDependency(subproject, 'detekt')
    }

    // AGP already wires a module's own `lint` into its `check` by default; this is
    // an explicit fleet-wide restatement of that (see the comment where this is
    // called from apply()), not a substitute for AGP's own wiring.
    private static void wireLintFleetWide(Project project) {
        project.rootProject.subprojects.each { subproject ->
            ['com.android.application', 'com.android.library'].each { pluginId ->
                subproject.pluginManager.withPlugin(pluginId) {
                    wireCheckDependency(subproject, 'lint')
                    wireVerifyLintResultsClean(subproject)
                }
            }
        }
    }

    // See VerifyLintResultsCleanTask's own doc comment for what/why. finalizedBy
    // (not dependsOn) so this still runs - and still reports - even if `lint`
    // itself already failed the build on a fatal-severity issue; and the file
    // collection is a live view over build/reports resolved at execution time,
    // not configuration time, so it sees whatever `lint` actually just wrote
    // regardless of relocateBuildDirs() having moved buildDir off to
    // D:\tmp\gradle-builds (see gradle_build_output_redirect).
    private static void wireVerifyLintResultsClean(Project subproject) {
        def verifyTask = subproject.tasks.register('verifyLintResultsClean', VerifyLintResultsCleanTask) { task ->
            task.group = 'verification'
            task.description = 'Fails the build if lint reported any issue at all, at any severity.'
            // Explicit dependsOn (not just the finalizedBy below) so Gradle's own
            // task-validation doesn't flag this as an implicit dependency - the
            // file collection below reads report files that are declared outputs
            // of `lint`'s own dependencies (lintReport<Variant>), and Gradle wants
            // that relationship stated, not just inferred from execution order.
            task.dependsOn(subproject.tasks.named('lint'))
            task.lintXmlReports.from(
                    subproject.layout.buildDirectory.asFileTree.matching {
                        it.include('reports/lint-results-*.xml')
                    }
            )
        }
        subproject.tasks.named('lint') { it.finalizedBy(verifyTask) }
    }

    // Task-name-based (not TaskProvider-based) so it stays a no-op if the named task
    // never actually gets registered on this subproject, rather than throwing -
    // matches.named's lazy "throw only if actually realized without existing"
    // semantics without callers here needing their own try/catch.
    //
    // Also matches on 'check' itself, rather than tasks.named('check') - this is called
    // directly from apply() (line 92, for cpdCheck), before the caller's own `check` task
    // is guaranteed to exist yet: a consuming build.gradle's `plugins {}` block resolves
    // top-to-bottom, and com.batodev.releasetools can be listed ahead of com.android.*/
    // java (whichever supplies `check` via the base plugin) - confirmed by
    // antimine_with_pics_as_prizes' :app, where it was. tasks.named('check') requires the
    // task to already be registered; tasks.matching{}.configureEach{} instead reacts
    // whenever a task named 'check' shows up, so this plugin's own application order
    // relative to the base/Android plugin no longer matters.
    private static void wireCheckDependency(Project project, String taskName) {
        project.tasks.matching { it.name == 'check' }.configureEach { task ->
            task.dependsOn(project.tasks.matching { it.name == taskName })
        }
    }

    // 50 tokens (vs PMD CPD's own default of 100) matches the threshold
    // screws_android's original hand-rolled task used - loose enough to skip
    // trivial boilerplate but tight enough to catch smaller copy-paste blocks.
    // The plugin only scans the project it's applied to (an app/android module)
    // by default; consuming repos are multi-module (core/android/lwjgl3/... for
    // libGDX-style projects), so the source is pointed at every subproject's
    // actual configured source dirs instead, or cross-module duplication would
    // go undetected.
    private static void configureCpd(Project project) {
        project.extensions.configure('cpd') { extension ->
            extension.language = 'kotlin'
            extension.minimumTokenCount = 50
            extension.toolVersion = pmdVersion(project)
        }
        project.tasks.named('cpdCheck') { task ->
            task.source = project.rootProject.subprojects.collectMany { mainSourceDirs(it) }
            // QQWing is a faithful port of the third-party QQWing solver/generator
            // (see the "CHECKED!" comments tracking manual verification against the
            // original); its row/column/section methods are intentionally parallel
            // implementations, not accidental copy-paste, so refactoring them to
            // dedupe risks correctness regressions in the puzzle solver for no
            // real benefit.
            task.exclude '**/core/qqwing/QQWing.kt'
            // name.boyle.chris.sgtpuzzles is vendored upstream - Chris Boyle's
            // Android port of Simon Tatham's Portable Puzzle Collection, which
            // sgtpuzzles/blocknetpuzzle is built on. Its GameEngine interface and
            // ConfigBuilder deliberately mirror the same config-setter signatures
            // (an API contract, not accidental copy-paste); deduping vendored
            // upstream would diverge it from upstream for no benefit - same
            // rationale as QQWing above.
            task.exclude '**/name/boyle/chris/**'
            // mainSourceDirs() above can include a subproject's generated-sources dir
            // (e.g. com.github.gmazzo.buildconfig's generateBuildConfigClasses output,
            // or screws_android's own ios:generateAdIds), which doesn't exist until
            // that subproject's own generation task has run. Gradle's task-validation
            // (enabled by the configuration cache) flags reading such a directory
            // without a declared dependency as an "implicit dependency", since nothing
            // then guarantees generation happens before this task scans it. Named
            // explicitly rather than matched generically (e.g. by task name pattern or
            // output-path overlap) - a consuming repo adding another generated-sources
            // task is rare enough that a one-line addition here when it happens is
            // simpler than generic matching logic every subproject's tasks would pay
            // the configuration-time cost of.
            List<String> generatedSourceTaskNames = ['generateBuildConfigClasses', 'generateAdIds']
            task.dependsOn(project.rootProject.subprojects.collectMany { subproject ->
                subproject.tasks.matching { generatedSourceTaskNames.contains(it.name) }
            })
        }
    }

    // Single source of truth for the PMD engine version is `pmd` in
    // release-tools/gradle/libs.versions.toml - read at apply-time rather than
    // hardcoded a second time here, where it would silently drift out of sync.
    private static String pmdVersion(Project project) {
        File toml = new File(project.rootProject.projectDir, "../release-tools/gradle/libs.versions.toml")
        def match = toml.text =~ /(?m)^pmd\s*=\s*"([^"]+)"/
        if (!match.find()) {
            throw new IllegalStateException("Could not find `pmd` version in ${toml}")
        }
        match.group(1)
    }

    // Reads each subproject's *actual* configured main source dirs instead of
    // guessing `src/main/java`/`src/main/kotlin` by convention - those happen to
    // match every subproject here today, but silently miss anything that
    // customizes its source layout. Android app/library subprojects expose
    // `android.sourceSets` (AGP's own source-set model, not a standard
    // SourceSetContainer); plain Kotlin/Java subprojects (java-library +
    // org.jetbrains.kotlin.jvm) expose the standard one instead - a subproject
    // has exactly one of the two, so both are probed and whichever exists wins.
    private static List<File> mainSourceDirs(Project subproject) {
        List<File> dirs = []

        def androidMain = subproject.extensions.findByName('android')?.sourceSets?.findByName('main')
        if (androidMain != null) {
            dirs.addAll(androidMain.java.srcDirs)
            dirs.addAll(kotlinSrcDirs(androidMain))
        }

        def javaMain = subproject.extensions.findByType(SourceSetContainer)?.findByName('main')
        if (javaMain != null) {
            dirs.addAll(javaMain.java.srcDirs)
            dirs.addAll(kotlinSrcDirs(javaMain))
        }

        dirs.findAll { it.exists() }
    }

    // The `kotlin` source-dir accessor only exists on a source set once the
    // Kotlin Gradle plugin has actually decorated it - probed rather than
    // assumed so pure-Java subprojects (e.g. `ios`/`lwjgl3` launchers) don't
    // blow up here.
    private static List<File> kotlinSrcDirs(sourceSet) {
        try {
            return new ArrayList<File>(sourceSet.kotlin.srcDirs)
        } catch (MissingPropertyException ignored) {
            return []
        }
    }

    // service account credentials file lives one level above every game's repo root
    // (i.e. directly under the Box Sync workspace root), alongside keystore.properties.
    private static void configurePlayPublishing(Project project) {
        project.extensions.configure('play') { extension ->
            extension.serviceAccountCredentials.set(
                    project.rootProject.file("../play-console-api-465319-0f9c399097c5.json"))
            extension.track.set("internal")
            extension.defaultToAppBundles.set(true)
        }
    }

    // com.github.ben-manes.versions' "dependencyUpdates" task treats any newer
    // version as "outdated" by default - alpha/beta/rc/M/SNAPSHOT included.
    // Without this filter, its report (and anyone reading it, human or tool)
    // would flag real dependencies against pre-release versions (observed: appcompat
    // 1.8.0-alpha01, navigation 2.10.0-alpha06, media3-exoplayer 1.11.0-rc01 across
    // most of the workspace). Reject any candidate that looks unstable unless the
    // *current* version is itself already unstable, per the plugin's own documented
    // pattern (README: "RejectVersionsIf and componentSelection").
    // pluginManager.withPlugin fires regardless of whether ben-manes.versions was applied
    // before or after this plugin in the consuming project's plugins {} block.
    private static void restrictDependencyUpdatesToStable(Project project) {
        project.pluginManager.withPlugin("com.github.ben-manes.versions") {
            project.tasks.named("dependencyUpdates").configure { task ->
                task.rejectVersionIf {
                    isNonStable(it.candidate.version) && !isNonStable(it.currentVersion)
                }
            }
        }
    }

    private static boolean isNonStable(String version) {
        boolean stableKeyword = ['RELEASE', 'FINAL', 'GA'].any { version.toUpperCase().contains(it) }
        boolean isStableVersion = version ==~ /^[0-9,.v-]+(-r)?$/
        !stableKeyword && !isStableVersion
    }

    // These workspaces live inside a Box Sync-synced folder, which intermittently
    // locks files under build/intermediates while syncing (observed as
    // "Couldn't delete ...R.jar" build failures). Redirecting build output to a
    // local, non-synced directory avoids that entirely.
    //
    // The redirect target must share a drive letter with the project: KSP2's Analysis API
    // worker throws "this and base files have different roots" when relativizing a generated
    // source (e.g. BuildConfig.java, which lives under buildDir) against the project dir if
    // they're on different drives (https://github.com/google/ksp/issues/1079) - and KSP1,
    // which didn't have that codepath, was removed as of KSP 2.3.x, so there's no escape
    // hatch via ksp.useKSP2=false anymore. java.io.tmpdir (usually the user's profile on C:)
    // can't guarantee that, so the temp root is derived from the project's own drive instead.
    private static void relocateBuildDirs(Project project) {
        String repoName = project.rootDir.name
        File externalRoot = new File(buildOutputBase(project), repoName)
        project.rootProject.layout.buildDirectory.set(new File(externalRoot, "root"))
        // Relocate every subproject, not just the one applying this plugin - otherwise
        // sibling modules the plugin doesn't run in (e.g. library modules in a multi-module
        // repo where only :app applies it) keep building into their own local build/ inside
        // the synced tree, defeating the point of this redirect for the whole repo.
        project.rootProject.subprojects.each { subproject ->
            subproject.layout.buildDirectory.set(new File(externalRoot, subproject.name))
        }
    }

    // Sibling to the project on its own drive rather than inside it, so it's still outside
    // the Box Sync sync tree - but same drive, so KSP2 can always relativize against it.
    // Deliberately not a fixed drive letter: derived from wherever the project is actually
    // checked out, so this keeps working if that ever differs machine to machine.
    private static File buildOutputBase(Project project) {
        File driveRoot = project.rootDir.toPath().root.toFile()
        new File(driveRoot, "tmp/gradle-builds")
    }

    private static boolean wasRequested(Project project, String taskName) {
        project.gradle.startParameter.taskNames.any {
            it == taskName || it.endsWith(":${taskName}" as String)
        }
    }

    private static void bumpVersion(File file) {
        if (!file.exists()) {
            throw new IllegalStateException(
                    "Expected ${file} to exist with versionCode/versionName properties.")
        }
        Properties props = new Properties()
        file.withInputStream { props.load(it) }

        int nextCode = Integer.parseInt(props.getProperty("versionCode").trim()) + 1
        String nextName = bumpMinor(props.getProperty("versionName").trim())

        props.setProperty("versionCode", String.valueOf(nextCode))
        props.setProperty("versionName", nextName)

        file.withOutputStream { props.store(it, "Bumped by createNewInternalTestVersion") }
    }

    // versionName follows a major.minor scheme only (no patch segment). Any existing
    // third segment is dropped rather than incremented.
    private static String bumpMinor(String version) {
        List<String> parts = version.split("\\.") as List<String>
        if (parts.size() < 2) {
            parts = parts + ["0"]
        } else if (parts.size() > 2) {
            parts = parts[0..1]
        }
        parts[1] = String.valueOf(Integer.parseInt(parts[1]) + 1)
        parts.join(".")
    }

    private static void commitVersionBump(ExecOperations execOperations, File rootDir, File versionPropsFile) {
        Properties props = new Properties()
        versionPropsFile.withInputStream { props.load(it) }
        String versionName = props.getProperty("versionName")

        // "git commit" with no pathspec commits the *entire* index, not just what
        // was just "git add"-ed - if the repo already has unrelated files staged
        // (observed on antimine_with_pics_as_prizes), those would get swept into
        // this commit too. Scoping both calls to the exact file avoids that.
        execOperations.exec { spec ->
            spec.workingDir = rootDir
            spec.commandLine = ["git", "add", versionPropsFile.absolutePath]
        }
        execOperations.exec { spec ->
            spec.workingDir = rootDir
            spec.commandLine = ["git", "commit", "-m", "rel: ${versionName}" as String, "--", versionPropsFile.absolutePath]
        }
    }
}
