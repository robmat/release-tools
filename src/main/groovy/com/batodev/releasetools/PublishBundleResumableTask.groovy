package com.batodev.releasetools

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction

/**
 * publishReleaseBundle's stand-in for big bundles (see PlayApi): uploads the release bundle to a new edit with a resumable
 * upload, puts it on the track as a completed release - named by version.properties' versionName, with the release notes
 * Gradle Play Publisher would read (src/main/play/release-notes/<language>/<track>.txt, else default.txt, as
 * -PwhatsNew writes) - and commits the edit.
 *
 * A project opts in through `releaseTools { publishTaskName.set("publishReleaseBundleResumable") }`, so
 * createNewInternalTestVersion publishes through it. With -PplayDryRun it uploads, sets the track and validates the edit,
 * then deletes it: nothing is released, and the versionCode stays unused.
 */
abstract class PublishBundleResumableTask extends DefaultTask {

    @InputFile
    abstract RegularFileProperty getBundle()

    @Internal
    abstract RegularFileProperty getCredentials()

    @Input
    abstract Property<String> getPackageName()

    @Input
    abstract Property<String> getTrack()

    @Internal
    abstract RegularFileProperty getVersionPropsFile()

    @Internal
    abstract DirectoryProperty getReleaseNotesDir()

    @Input
    abstract Property<Boolean> getDryRun()

    @TaskAction
    void publish() {
        String app = packageName.get()
        PlayApi play = new PlayApi(credentials.get().asFile, logger)
        String edit = play.createEdit(app)
        boolean committed = false
        try {
            File file = bundle.get().asFile
            logger.lifecycle("Uploading ${file.name} (${file.length().intdiv(1_000_000)} MB) to ${app}")
            long versionCode = play.uploadBundle(app, edit, file)
            play.setTrack(app, edit, track.get(), release(versionCode))
            if (dryRun.get()) {
                play.validate(app, edit)
                logger.lifecycle("Dry run: the edit validated; deleting it, nothing released")
            } else {
                play.commit(app, edit)
                committed = true
                logger.lifecycle("Released versionCode ${versionCode} on the '${track.get()}' track")
            }
        } finally {
            if (!committed) {
                discard(play, app, edit)
            }
        }
    }

    // An edit left behind expires by itself within days, so a failure to delete it must not hide the error that got here.
    private void discard(PlayApi play, String app, String edit) {
        try {
            play.deleteEdit(app, edit)
        } catch (IOException failed) {
            logger.warn("Could not delete Play edit ${edit}: ${failed.message}")
        }
    }

    private Map release(long versionCode) {
        Map release = [versionCodes: [String.valueOf(versionCode)], status: "completed"]
        String name = versionName()
        if (name) {
            release.name = name
        }
        List<Map> notes = releaseNotes()
        if (notes) {
            release.releaseNotes = notes
        }
        release
    }

    private String versionName() {
        File file = versionPropsFile.present ? versionPropsFile.get().asFile : null
        if (file == null || !file.exists()) {
            return null
        }
        Properties props = new Properties()
        file.withInputStream { props.load(it) }
        props.getProperty("versionName")
    }

    private List<Map> releaseNotes() {
        File dir = releaseNotesDir.present ? releaseNotesDir.get().asFile : null
        List<File> languages = (dir?.listFiles() ?: new File[0]).findAll { it.directory }
        languages.collect { File language ->
            File notes = [new File(language, "${track.get()}.txt"), new File(language, "default.txt")].find { it.exists() }
            notes ? [language: language.name, text: notes.text.trim()] : null
        }.findAll { it != null }
    }
}
