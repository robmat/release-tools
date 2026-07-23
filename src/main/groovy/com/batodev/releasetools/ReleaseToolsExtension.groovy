package com.batodev.releasetools

import org.gradle.api.provider.Property

/**
 * Configures the release-tools plugin for projects that don't use the plain,
 * unflavored "publishReleaseBundle" task - e.g. a flavored :app module where
 * Gradle Play Publisher instead generates "publishGoogleReleaseBundle" etc.
 *
 * Usage in a consuming build.gradle:
 *   releaseTools {
 *       publishTaskName.set("publishGoogleReleaseBundle")
 *       promoteTaskName.set("promoteGoogleReleaseArtifact")
 *   }
 */
abstract class ReleaseToolsExtension {
    abstract Property<String> getPublishTaskName()

    abstract Property<String> getPromoteTaskName()
}
