package com.batodev.releasetools

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.internal.os.OperatingSystem
import org.gradle.process.ExecOperations

import javax.inject.Inject

/**
 * Shells out to this project's own Gradle wrapper to run the Play Publisher
 * promote task with fixed, known-correct flags. Configuring the underlying
 * promoteReleaseArtifact task's from/to track properties directly isn't done
 * here because those aren't part of Gradle Play Publisher's documented public
 * API - the CLI flags are, so we drive it the same way a human would.
 *
 * repoRootDir/taskPathPrefix/repoDisplayName are captured as task properties
 * set at configuration time (see ReleaseToolsPlugin's registration) rather than
 * read via `project.rootDir`/`project.path`/`project.name`/`project.logger`
 * inside @TaskAction - the config cache forbids `Task.project` access at
 * execution time.
 */
abstract class PromoteInternalToProdTask extends DefaultTask {

    @Inject
    abstract ExecOperations getExecOperations()

    @Input
    abstract Property<String> getPromoteTaskName()

    @Internal
    abstract DirectoryProperty getRepoRootDir()

    @Internal
    abstract Property<String> getTaskPathPrefix()

    @Internal
    abstract Property<String> getRepoDisplayName()

    @TaskAction
    void promote() {
        File rootDir = repoRootDir.get().asFile
        String taskPath = "${taskPathPrefix.get()}:${promoteTaskName.get()}"
        List<String> wrapperArgs = [
                taskPath,
                "--from-track", "internal",
                "--promote-track", "production",
        ]

        logger.lifecycle("Promoting ${repoDisplayName.get()} from 'internal' to 'production'...")

        execOperations.exec { spec ->
            spec.workingDir = rootDir
            if (OperatingSystem.current().windows) {
                // .bat files aren't directly executable via CreateProcess - they
                // need cmd /c to interpret them, otherwise this fails with
                // "CreateProcess error=2" regardless of workingDir/PATH.
                spec.commandLine = ["cmd", "/c", ".\\gradlew.bat"] + wrapperArgs
            } else {
                spec.commandLine = ["./gradlew"] + wrapperArgs
            }
        }
    }
}
