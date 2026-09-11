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

    // Only set (by ReleaseToolsPlugin) when -PwhatsNew was actually used for this
    // invocation - see writeWhatsNewIfProvided(). Optional so a plain release (no
    // whatsNew) leaves this unset and skips the cleanup step below entirely.
    @Internal
    abstract RegularFileProperty getReleaseNotesFile()

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

        deleteWhatsNewScratchFile()
    }

    // writeWhatsNewIfProvided() (see ReleaseToolsPlugin) wrote this file into the source
    // tree only so publishReleaseBundle's execution - which happens before this task's own
    // action runs, per the dependsOn above - would pick it up as that one build's release
    // notes. Left in place afterward it just shows up as an untracked/dirty file every
    // release (it was never meant to be a committed, reusable notes template), so it's
    // deleted here now that publishReleaseBundle has already consumed it.
    private void deleteWhatsNewScratchFile() {
        if (!releaseNotesFile.isPresent()) {
            return
        }
        File notesFile = releaseNotesFile.get().asFile
        notesFile.delete()
        File notesDir = notesFile.parentFile
        if (notesDir.exists() && notesDir.list().length == 0) {
            notesDir.delete()
        }
    }
}
