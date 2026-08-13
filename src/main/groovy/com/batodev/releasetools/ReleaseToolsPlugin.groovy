package com.batodev.releasetools

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.process.ExecOperations

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

        project.tasks.register("createNewInternalTestVersion") { task ->
            task.group = "publishing"
            task.description = "Bumps versionCode/versionName in version.properties, then builds and publishes to the internal testing track."
            task.dependsOn(extension.publishTaskName)
            // doLast only runs once publishReleaseBundle (a dependency) has actually
            // succeeded - if the build fails partway (e.g. a compile error), Gradle
            // aborts before this runs, so a failed release leaves an uncommitted bump
            // on disk instead of a commit falsely recording a published release.
            task.doLast {
                commitVersionBump(execOperations, project, versionPropsFile)
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
        return match.group(1)
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

        return dirs.findAll { it.exists() }
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
        return !stableKeyword && !isStableVersion
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
        project.layout.buildDirectory.set(new File(externalRoot, project.name))
    }

    // Sibling to the project on its own drive rather than inside it, so it's still outside
    // the Box Sync sync tree - but same drive, so KSP2 can always relativize against it.
    // Deliberately not a fixed drive letter: derived from wherever the project is actually
    // checked out, so this keeps working if that ever differs machine to machine.
    private static File buildOutputBase(Project project) {
        File driveRoot = project.rootDir.toPath().getRoot().toFile()
        return new File(driveRoot, "tmp/gradle-builds")
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
        return parts.join(".")
    }

    private static void commitVersionBump(ExecOperations execOperations, Project project, File versionPropsFile) {
        Properties props = new Properties()
        versionPropsFile.withInputStream { props.load(it) }
        String versionName = props.getProperty("versionName")

        // "git commit" with no pathspec commits the *entire* index, not just what
        // was just "git add"-ed - if the repo already has unrelated files staged
        // (observed on antimine_with_pics_as_prizes), those would get swept into
        // this commit too. Scoping both calls to the exact file avoids that.
        execOperations.exec { spec ->
            spec.workingDir = project.rootDir
            spec.commandLine = ["git", "add", versionPropsFile.absolutePath]
        }
        execOperations.exec { spec ->
            spec.workingDir = project.rootDir
            spec.commandLine = ["git", "commit", "-m", "rel: ${versionName}" as String, "--", versionPropsFile.absolutePath]
        }
    }
}
