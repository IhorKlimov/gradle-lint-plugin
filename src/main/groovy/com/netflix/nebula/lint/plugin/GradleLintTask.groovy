package com.netflix.nebula.lint.plugin

import com.netflix.nebula.lint.rule.GradleLintRule
import org.codenarc.analyzer.StringSourceAnalyzer
import org.codenarc.rule.Rule
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.TaskAction
import org.gradle.logging.StyledTextOutput
import org.gradle.logging.StyledTextOutputFactory
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import javax.inject.Inject

class GradleLintTask extends DefaultTask {
    Logger taskLogger = LoggerFactory.getLogger(GradleLintTask)

    @Inject
    protected StyledTextOutputFactory getTextOutputFactory() {
        null // see http://gradle.1045684.n5.nabble.com/injecting-dependencies-into-task-instances-td5712637.html
    }

    static def mergeBySum(Map... m) {
        m.collectMany { it.entrySet() }.inject([:]) { result, e ->
            result << [(e.key): e.value + (result[e.key] ?: 0)]
        }
    }

    @TaskAction
    void lint() {
        def textOutput = textOutputFactory.create('lint')

        def registry = new LintRuleRegistry()

        def violationsByProject = [:]
        def totalBySeverity = [warning: 0, error: 0]

        ([project] + project.subprojects).each { p ->
            if(p.buildFile.exists()) {
                def extension
                try {
                    extension = p.extensions.getByType(GradleLintExtension)
                } catch(UnknownDomainObjectException) {
                    // if the subproject has not applied lint, use the extension configuration from the root project
                    extension = p.rootProject.extensions.getByType(GradleLintExtension)
                }
                def ruleSet = RuleSetFactory.configureRuleSet(extension.rules.collect { registry.buildRules(it, p) }
                        .flatten() as List<Rule>)

                if(taskLogger.isDebugEnabled()) {
                    ruleSet.rules.each {
                        if(it instanceof GradleLintRule)
                            taskLogger.debug('Executing {} against {}', it.ruleId, p.buildFile.path)
                    }
                }

                violationsByProject[p] = new StringSourceAnalyzer(p.buildFile.text).analyze(ruleSet).violations

                totalBySeverity = mergeBySum(totalBySeverity, violationsByProject[p].countBy {
                    it.rule.priority <= 3 ? 'warning' : 'error'
                })
            }
        }

        def allViolations = violationsByProject.values().flatten()

        if (!allViolations.isEmpty()) {
            textOutput.withStyle(StyledTextOutput.Style.UserInput).text('\nThis project contains lint violations. ')
            textOutput.println('A complete listing of the violations follows. ')

            if (totalBySeverity.error) {
                textOutput.text('Because some were serious, the overall build status has been changed to ')
                        .withStyle(StyledTextOutput.Style.Failure).println("FAILED\n")
            } else {
                textOutput.println('Because none were serious, the build\'s overall status was unaffected.\n')
            }
        }

        violationsByProject.entrySet().each {
            def buildFilePath = it.key.rootDir.toURI().relativize(it.key.buildFile.toURI()).toString()
            def violations = it.value

            violations.each { v ->
                def severity = v.rule.priority <= 3 ? 'warning' : 'error'

                textOutput.withStyle(StyledTextOutput.Style.Failure).text(severity.padRight(10))
                textOutput.text(v.rule.ruleId.padRight(35))
                textOutput.withStyle(StyledTextOutput.Style.Description).println(v.message)

                textOutput.withStyle(StyledTextOutput.Style.UserInput).println(buildFilePath + ':' + v.lineNumber)
                textOutput.println("$v.sourceLine\n") // extra space between violations
            }

            def errors = totalBySeverity.error ?: 0
            def warnings = totalBySeverity.warning ?: 0
            if(!violations.isEmpty()) {
                textOutput.withStyle(StyledTextOutput.Style.Failure)
                        .println("\u2716 ${buildFilePath}: ${violations.size()} problem${violations.size() == 1 ? '' : 's'} ($errors error${errors == 1 ? '' : 's'}, $warnings warning${warnings == 1 ? '' : 's'})\n".toString())
            }
        }

        if (!allViolations.isEmpty()) {
            textOutput.text("To apply fixes automatically, run ").withStyle(StyledTextOutput.Style.UserInput).text("fixGradleLint")
            textOutput.println(", review, and commit the changes.\n")

            if (totalBySeverity.error)
                throw new LintCheckFailedException() // fail the whole build
        }
    }
}
