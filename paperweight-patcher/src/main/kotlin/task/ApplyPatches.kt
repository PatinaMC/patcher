/*
 * MIT License

 * Copyright (c) 2020-2021 Jason Penilla & Contributors

 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:

 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.

 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package task

import ensureSuccess
import forkName
import gitCmd
import org.gradle.api.Project
import org.gradle.api.Task
import reEnableGitSigning
import taskGroup
import temporarilyDisableGitSigning
import toothpick
import upstreams
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

internal fun Project.createApplyPatchesTask(
    receiver: Task.() -> Unit = {}
): Task = tasks.create("applyPatches_") {
    receiver(this)
    group = taskGroup

    fun checkCursed(project: Project): Boolean {
        return project.properties.getOrDefault("cursed", "false").toString().toBoolean()
    }

    fun applyPatches(patchDir: Path, applyName: String, name: String, wasGitSigningEnabled: Boolean, projectDir: File): Boolean {
        if (Files.notExists(patchDir)) return true

        val patchPaths = Files.newDirectoryStream(patchDir)
            .map { it.toFile() }
            .filter { it.name.endsWith(".patch") }
            .sorted()
            .takeIf { it.isNotEmpty() } ?: return true
        val patches = patchPaths.map { it.absolutePath }.toTypedArray()

        logger.lifecycle(">>> Applying $applyName patches to $name")

        gitCmd("am", "--abort")

        //Cursed Apply Mode that makes fixing stuff a lot easier
        if (checkCursed(project)) {
            for (patch in patches) {
                val gitCommand = arrayListOf("am", "--3way", "--ignore-whitespace",
                    "--rerere-autoupdate", "--whitespace=fix", "--reject", "-C0", patch)
                if (gitCmd(*gitCommand.toTypedArray(), dir = projectDir, printOut = true).exitCode != 0) {
                    gitCmd("add", ".", dir = projectDir, printOut = true)
                    gitCmd("am", "--continue", dir = projectDir, printOut = true)
                }
            }
        } else {
            val gitCommand = arrayListOf("am", "--3way", "--ignore-whitespace",
                "--rerere-autoupdate", "--whitespace=fix",  *patches)
            ensureSuccess(gitCmd(*gitCommand.toTypedArray(), dir = projectDir, printOut = true)) {
                if (wasGitSigningEnabled) reEnableGitSigning(projectDir)
            }
        }
        return false;
    }

    doLast {
        for ((name, subproject) in toothpick.subprojects) {
            val (sourceRepo, projectDir, patchesDir) = subproject

            val folder = (if (patchesDir.endsWith("server")) "server" else "api")

            // Reset or initialize subproject
            logger.lifecycle(">>> Resetting subproject $name")
            if (projectDir.exists()) {
                ensureSuccess(gitCmd("fetch", "origin", dir = projectDir))
                ensureSuccess(gitCmd("reset", "--hard", "origin/master", dir = projectDir))
            } else {
                ensureSuccess(gitCmd("clone", sourceRepo.absolutePath, projectDir.absolutePath, printOut = true))
            }
            logger.lifecycle(">>> Done resetting subproject $name")

            val wasGitSigningEnabled = temporarilyDisableGitSigning(projectDir)

            for (upstream in upstreams) {
                if (((folder == "server" && upstream.serverList?.isEmpty() != false) || (folder == "api" && upstream.apiList?.isEmpty() != false)) && !upstream.useBlackList) continue
                if (((folder == "server" && upstream.getRepoServerPatches()?.isEmpty() != false) || (folder == "api" && upstream.getRepoAPIPatches()?.isEmpty() != false)) && upstream.useBlackList) continue
                project.gitCmd("branch", "-D", "${upstream.name}-$folder", dir = projectDir)
                project.gitCmd("checkout", "-b", "${upstream.name}-$folder", dir = projectDir)
                // Apply patches
                val patchDir = Paths.get("${upstream.patchPath}/$folder")

                if (applyPatches(patchDir, upstream.name, name, wasGitSigningEnabled, projectDir)) continue
            }
            // project.gitCmd("branch", "-D", "$forkName-$folder", dir = projectDir)
            // project.gitCmd("checkout", "-b", "$forkName-$folder", dir = projectDir)
            project.gitCmd("branch", "-D", "master", dir = projectDir)
            project.gitCmd("checkout", "-b", "master", dir = projectDir)
            val patchDir = patchesDir.toPath()
            // Apply patches
            if (applyPatches(patchDir, forkName, name, wasGitSigningEnabled, projectDir)) continue

            if (wasGitSigningEnabled) reEnableGitSigning(projectDir)
            logger.lifecycle(">>> Done applying patches to $name")
        }
    }
}
