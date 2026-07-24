package com.batodev.releasetools

import org.gradle.api.DefaultTask
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
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
 */
abstract class PromoteInternalToProdTask extends DefaultTask {

    @Inject
    abstract ExecOperations getExecOperations()

    @Input
    abstract Property<String> getPromoteTaskName()

    @TaskAction
    void promote() {
        File rootDir = project.rootDir
        String taskPath = "${project.path}:${promoteTaskName.get()}"
        List<String> wrapperArgs = [
                taskPath,
                "--from-track", "internal",
                "--promote-track", "production",
        ]

        project.logger.lifecycle("Promoting ${project.name} from 'internal' to 'production'...")

        execOperations.exec { spec ->
            spec.workingDir = rootDir
            if (OperatingSystem.current().isWindows()) {
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
