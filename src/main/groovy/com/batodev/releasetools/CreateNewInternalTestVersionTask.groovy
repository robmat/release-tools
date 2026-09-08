package com.batodev.releasetools

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations

import javax.inject.Inject

/**
 * Commits the versionCode/versionName bump (already written to version.properties
 * during configuration - see ReleaseToolsPlugin.bumpVersion) once the publish this
 * task depends on has actually succeeded. A dedicated Task class (not a closure
 * capturing the plugin instance's constructor-injected ExecOperations, which this
 * task used to be) - such closures don't survive configuration-cache serialization
 * of task actions ("Could not get unknown property 'execOperations' for task ...
 * of type org.gradle.api.DefaultTask" on a cache-reuse run), matching
 * PromoteInternalToProdTask's own pattern.
 *
 * repoRootDir is captured as a task property set at configuration time (see
 * ReleaseToolsPlugin's registration) rather than read via `project.rootDir`
 * inside @TaskAction - the config cache forbids `Task.project` access at
 * execution time.
 */
abstract class CreateNewInternalTestVersionTask extends DefaultTask {

    @Inject
    abstract ExecOperations getExecOperations()

    @InputFile
    abstract RegularFileProperty getVersionPropsFile()

    @Internal
    abstract DirectoryProperty getRepoRootDir()

    @TaskAction
    void commitVersionBump() {
        File rootDir = repoRootDir.get().asFile
        File file = versionPropsFile.get().asFile
        Properties props = new Properties()
        file.withInputStream { props.load(it) }
        String versionName = props.getProperty("versionName")

        // "git commit" with no pathspec commits the *entire* index, not just what
        // was just "git add"-ed - if the repo already has unrelated files staged
        // (observed on antimine_with_pics_as_prizes), those would get swept into
        // this commit too. Scoping both calls to the exact file avoids that.
        execOperations.exec { spec ->
            spec.workingDir = rootDir
            spec.commandLine = ["git", "add", file.absolutePath]
        }
        execOperations.exec { spec ->
            spec.workingDir = rootDir
            spec.commandLine = ["git", "commit", "-m", "rel: ${versionName}" as String, "--", file.absolutePath]
        }
    }
}
