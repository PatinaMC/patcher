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

import LibraryImport
import ensureSuccess
import gitCmd
import internalTaskGroup
import libraryImports
import nmsImports
import org.gradle.api.Project
import org.gradle.api.Task
import toothpick
import upstreams
import java.io.File
import java.nio.file.Files
import kotlin.streams.toList

internal fun Project.createImportMCDevTask(
    receiver: Task.() -> Unit = {}
): Task = tasks.create("importMCDev_") {
    receiver(this)
    group = internalTaskGroup
    val upstreamServer = toothpick.serverProject.baseDir
    val importLog = arrayListOf("Extra mc-dev imports")

    fun isDuplicateImport(target: File, className: String): Boolean {
        if (!target.exists()) return false
        val message = "Skipped import for $className, a class with that name already exists in the source tree. Is there an extra entry in mcdevimports.json?"
        project.gradle.taskGraph.allTasks.last().doLast {
        logger.warn(message)
        }
        logger.warn(message)
        return true
    }

    fun importNMS(className: String) {
        logger.lifecycle("Importing $className")
        val classPath = "${className.replace(".", "/")}.java"
        val source = toothpick.paperDecompDir.resolve("spigot/$classPath")
        importLog.add("Importing $className")
        if (!source.exists()) error("Missing NMS: $className")
        val target = upstreamServer.resolve("src/main/java/$classPath")
        if (isDuplicateImport(target, className)) return
        target.parentFile.mkdirs()
        source.copyTo(target)
    }

    fun importLibrary(import: LibraryImport) {
        val (group, lib, prefix, file) = import
        val className = "${prefix.replace("/", ".")}.$file"
        importLog.add("Imported $className from $group.$lib")
        logger.lifecycle("Importing $className from $group.$lib")
        val source = toothpick.paperDecompDir.resolve("libraries/$group/$lib/$prefix/$file.java")
        if (!source.exists()) error("Missing Base: $lib $prefix/$file")
        val targetDir = upstreamServer.resolve("src/main/java/$prefix")
        val target = targetDir.resolve("$file.java")
        if (isDuplicateImport(target, className)) return
        targetDir.mkdirs()
        source.copyTo(target)
    }

    fun findNeededImports(patches: List<File>): Set<String> = patches.asSequence()
        .flatMap { it.readLines().asSequence() }
        .filter { line ->
        line.startsWith("+++ b/src/main/java/net/minecraft/")
            || line.startsWith("+++ b/src/main/java/com/mojang/math/")
        }
        .distinct()
        .map { it.substringAfter("+++ b/src/main/java/") }
        .filter { !upstreamServer.resolve("src/main/java/$it").exists() }
        .filter {
        val sourceFile = toothpick.paperDecompDir.resolve("spigot/$it")
        val exists = sourceFile.exists()
        if (!sourceFile.exists()) logger.lifecycle("$it is either missing, or is a new file added through a patch")
        exists
        }
        .map { it.replace("/", ".").substringBefore(".java") }
        .toSet()

    fun getAndApplyNMS(patchesDir: File) {
        findNeededImports(patchesDir.listFiles().toList()).toList().forEach(::importNMS)
    }

    doLast {
        logger.lifecycle(">>> Importing mc-dev")
        val lastCommitIsMCDev = gitCmd(
            "log", "-1", "--oneline",
            dir = upstreamServer
        ).output?.contains("Extra mc-dev imports") == true
        if (lastCommitIsMCDev) {
            ensureSuccess(
                gitCmd(
                    "reset", "--hard", "HEAD~1",
                    dir = upstreamServer,
                    printOut = true
                )
            )
        }
        for (upstream in upstreams) {
            val patchesDir = rootProject.projectDir.resolve("${upstream.patchPath}/server")
            getAndApplyNMS(patchesDir)
        }

        val patchesDir = toothpick.serverProject.patchesDir
        getAndApplyNMS(patchesDir)


        // Imports from MCDevImports.kt
        nmsImports.forEach(::importNMS)
        libraryImports.forEach(::importLibrary)

        val add = gitCmd("add", ".", "-A", dir = upstreamServer).exitCode == 0
        val commit = gitCmd("commit", "-m", importLog.joinToString("\n"), dir = upstreamServer).exitCode == 0
        if (!add || !commit) {
            logger.lifecycle(">>> Didn't import any extra files")
        }
        logger.lifecycle(">>> Done importing mc-dev")
    }
}
