package com.batodev.releasetools

import org.gradle.api.Plugin
import org.gradle.api.Project
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

        restrictDependencyUpdatesToStable(project)
    }

    // com.github.ben-manes.versions' "dependencyUpdates" task (and, downstream,
    // se.patrikerdes.use-latest-versions' bumps, which just read its report) treats
    // any newer version as "outdated" by default - alpha/beta/rc/M/SNAPSHOT included.
    // Without this filter, useLatestVersions happily bumps real dependencies to
    // pre-release versions (observed: appcompat 1.8.0-alpha01, navigation 2.10.0-alpha06,
    // media3-exoplayer 1.11.0-rc01 across most of the workspace). Reject any candidate
    // that looks unstable unless the *current* version is itself already unstable, per
    // the plugin's own documented pattern (README: "RejectVersionsIf and componentSelection").
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
    private static void relocateBuildDirs(Project project) {
        String repoName = project.rootDir.name
        File externalRoot = new File("E:/tmp/gradle-builds/${repoName}")
        project.rootProject.layout.buildDirectory.set(new File(externalRoot, "root"))
        project.layout.buildDirectory.set(new File(externalRoot, project.name))
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
