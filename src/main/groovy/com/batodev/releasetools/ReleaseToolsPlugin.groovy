package com.batodev.releasetools

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Shared release-automation tasks for the game projects in this workspace.
 * Consumed as an included build via `pluginManagement { includeBuild(...) }`
 * in each project's settings.gradle - no publishing step required.
 */
class ReleaseToolsPlugin implements Plugin<Project> {

    @Override
    void apply(Project project) {
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
            task.dependsOn("publishReleaseBundle")
            // doLast only runs once publishReleaseBundle (a dependency) has actually
            // succeeded - if the build fails partway (e.g. a compile error), Gradle
            // aborts before this runs, so a failed release leaves an uncommitted bump
            // on disk instead of a commit falsely recording a published release.
            task.doLast {
                commitVersionBump(project, versionPropsFile)
            }
        }

        project.tasks.register("promoteInternalToProd", PromoteInternalToProdTask) { task ->
            task.group = "publishing"
            task.description = "Promotes the current internal testing release to production."
        }
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

    private static void commitVersionBump(Project project, File versionPropsFile) {
        Properties props = new Properties()
        versionPropsFile.withInputStream { props.load(it) }
        String versionName = props.getProperty("versionName")

        project.exec { spec ->
            spec.workingDir = project.rootDir
            spec.commandLine = ["git", "add", versionPropsFile.absolutePath]
        }
        project.exec { spec ->
            spec.workingDir = project.rootDir
            spec.commandLine = ["git", "commit", "-m", "rel: ${versionName}" as String]
        }
    }
}
