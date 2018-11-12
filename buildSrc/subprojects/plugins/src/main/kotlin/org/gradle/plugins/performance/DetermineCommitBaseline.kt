/*
 * Copyright 2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.plugins.performance

import org.gradle.api.DefaultTask
import org.gradle.api.Task
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.*
import org.gradle.testing.PerformanceTest


val commitVersionRegex = """(\d+(\.\d+)+)-commit-[a-f0-9]+""".toRegex()


fun Task.currentBranchIsMasterOrRelease() =
    when (project.stringPropertyOrNull(PropertyNames.branchName)) {
        "master" -> true
        "release" -> true
        else -> false
    }


fun Task.allPerformanceTestsHaveDefaultBaselines() =
    project.tasks
        .withType(PerformanceTest::class)
        .toList()
        .all { it.baselines.isNullOrEmpty() || it.baselines == "defaults" || it.baselines == Config.baseLineList }


fun Task.anyExplicitlySetPerformanceTestCommitBaseline() =
    project.tasks
        .withType(PerformanceTest::class)
        .map(PerformanceTest::getBaselines)
        .firstOrNull { true == it?.matches(commitVersionRegex) }


fun Task.forkPointCommitRequired() =
    !currentBranchIsMasterOrRelease() && allPerformanceTestsHaveDefaultBaselines()


open class DetermineCommitBaseline : DefaultTask() {
    init {
        onlyIf { forkPointCommitRequired() || anyExplicitlySetPerformanceTestCommitBaseline() != null }
    }

    @Internal
    val commitBaselineVersion = project.objects.property<String>()

    @TaskAction
    fun determineForkPointCommitBaseline() {
        if (forkPointCommitRequired()) {
            commitBaselineVersion.set(forkPointCommitBaseline())
            project.tasks.withType(PerformanceTest::class).forEach { it.baselines = commitBaselineVersion.get() }
        } else {
            commitBaselineVersion.set(anyExplicitlySetPerformanceTestCommitBaseline())
        }
    }

    private
    fun forkPointCommitBaseline(): String {
        project.execAndGetStdout("git", "fetch", "origin", "master", "release")
        val masterForkPointCommit = project.execAndGetStdout("git", "merge-base", "origin/master", "HEAD")
        val releaseForkPointCommit = project.execAndGetStdout("git", "merge-base", "origin/release", "HEAD")
        val forkPointCommit =
            if (project.exec { isIgnoreExitValue = true; commandLine("git", "merge-base", "--is-ancestor", masterForkPointCommit, releaseForkPointCommit) }.exitValue == 0)
                releaseForkPointCommit
            else
                masterForkPointCommit
        val baseVersionOnForkPoint = project.execAndGetStdout("git", "show", "$forkPointCommit:version.txt")
        val shortCommitId = project.execAndGetStdout("git", "rev-parse", "--short", forkPointCommit)
        return "$baseVersionOnForkPoint-commit-$shortCommitId"
    }
}
