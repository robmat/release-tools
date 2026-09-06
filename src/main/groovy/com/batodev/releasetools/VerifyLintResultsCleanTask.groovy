package com.batodev.releasetools

import groovy.xml.XmlSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Backstop for the fleet-wide "clean lint report" policy (see
 * LINT_CLEANUP_LEFTOVERS.md) - AGP's own `lint` task only fails the build on
 * fatal/error-severity issues by default, so a plain warning (including from a
 * newly introduced check, or a regression in code nobody re-ran lint on
 * manually) would otherwise pass `lint`/`check` silently. This re-parses
 * whatever lint-results-*.xml the `lint` task just wrote - the file name
 * varies by flavor/variant (e.g. lint-results-fossDebug.xml for a flavored
 * module, plain lint-results-debug.xml for a flavorless one) - and fails the
 * build if any of them contain so much as one &lt;issue&gt; element,
 * regardless of severity.
 */
abstract class VerifyLintResultsCleanTask extends DefaultTask {

    @InputFiles
    @Optional
    @PathSensitive(PathSensitivity.NONE)
    abstract ConfigurableFileCollection getLintXmlReports()

    @TaskAction
    void verify() {
        List<String> offenders = []
        lintXmlReports.files.each { file ->
            if (!file.exists()) {
                return
            }
            new XmlSlurper().parse(file).issue.each { issue ->
                offenders << "${file.name}: [${issue.@id}] ${issue.@message}"
            }
        }
        if (!offenders.empty) {
            throw new GradleException(
                    "lint reported ${offenders.size()} issue(s), but this project's policy is a " +
                            "clean lint report - fix it, or suppress it with a documented reason " +
                            "(see LINT_CLEANUP_LEFTOVERS.md):\n" +
                            offenders.collect { " - $it" }.join('\n')
            )
        }
    }
}
