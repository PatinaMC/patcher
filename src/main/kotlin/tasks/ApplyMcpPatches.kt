/*
 * paperweight is a Gradle plugin for the PaperMC project. It uses
 * some code and systems originally from ForgeGradle.
 *
 * Copyright (C) 2020 Kyle Wood
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301
 * USA
 */

package io.papermc.paperweight.tasks

import io.papermc.paperweight.util.Git
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import java.io.File

abstract class ApplyMcpPatches : ZippedTask() {

    @get:InputDirectory
    abstract val serverPatchDir: DirectoryProperty
    @get:InputFile
    abstract val configFile: RegularFileProperty

    override fun run(rootDir: File) {
        val git = Git(rootDir)

        val extension = ".java.patch"
        objects.fileTree().from(serverPatchDir).matching {
            include("**/*$extension")
        }.forEach { patch ->
            git("apply", "--ignore-whitespace", patch.absolutePath).executeSilently()
        }
    }
}