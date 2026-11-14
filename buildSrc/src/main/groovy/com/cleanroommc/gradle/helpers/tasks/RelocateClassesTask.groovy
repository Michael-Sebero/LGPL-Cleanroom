package com.cleanroommc.gradle.helpers.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.Remapper

import java.util.zip.ZipFile

/**
 * Copies class files from directories and jars into an output directory, rewriting package prefixes on the way.
 * <p>
 * A minimal stand-in for Shadow's relocation: used to embed a private copy of a library (AssetMover) so it can
 * never collide with a standalone copy of the same library that the user also has installed.
 * Non-class entries and META-INF are dropped.
 */
abstract class RelocateClassesTask extends DefaultTask {

    /** Class directories and/or jars to read from. */
    @InputFiles
    abstract ConfigurableFileCollection getSources()

    /** Internal-name prefixes, e.g. {@code com/example/lib/ -> com/me/shadow/lib/}. */
    @Input
    abstract MapProperty<String, String> getRelocations()

    @OutputDirectory
    abstract DirectoryProperty getOutputDirectory()

    @TaskAction
    void relocate() {
        def out = outputDirectory.get().asFile
        out.deleteDir()
        out.mkdirs()

        def rules = relocations.get().collect { k, v -> [k, v] }.sort { -it[0].length() }
        def remapper = new Remapper() {
            @Override
            String map(String internalName) {
                for (rule in rules) {
                    if (internalName.startsWith(rule[0])) {
                        return rule[1] + internalName.substring(rule[0].length())
                    }
                }
                return internalName
            }
        }

        def write = { String entryName, byte[] bytes ->
            if (!entryName.endsWith('.class') || entryName.startsWith('META-INF/')) {
                return
            }
            def reader = new ClassReader(bytes)
            def writer = new ClassWriter(0)
            reader.accept(new ClassRemapper(writer, remapper), 0)
            def target = new File(out, remapper.map(entryName.substring(0, entryName.length() - 6)) + '.class')
            target.parentFile.mkdirs()
            target.bytes = writer.toByteArray()
        }

        sources.files.each { File source ->
            if (source.isDirectory()) {
                source.eachFileRecurse { File f ->
                    if (f.isFile()) {
                        write(source.toPath().relativize(f.toPath()).toString().replace('\\', '/'), f.bytes)
                    }
                }
            } else if (source.isFile()) {
                new ZipFile(source).withCloseable { zip ->
                    zip.entries().each { entry ->
                        if (!entry.directory) {
                            write(entry.name, zip.getInputStream(entry).bytes)
                        }
                    }
                }
            }
        }
    }
}
