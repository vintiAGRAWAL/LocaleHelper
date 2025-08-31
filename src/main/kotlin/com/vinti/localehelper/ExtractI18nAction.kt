package com.vinti.localehelper

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.ui.Messages
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.io.FileReader
import java.io.FileWriter

class ExtractI18nAction : AnAction("Extract i18n") {

    private lateinit var projectBase: String
    private lateinit var cachePath: String

    private val suggestionCache: MutableMap<String, String> by lazy { loadCache() }

    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabledAndVisible = file?.extension == "slim"
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        projectBase = project.basePath ?: return
        cachePath = "$projectBase/.i18n_suggestions.json"

        val editor: Editor = CommonDataKeys.EDITOR.getData(e.dataContext) ?: return
        val selectionRaw = editor.selectionModel.selectedText ?: return

        val (cleanSelection, hasHtml) = preprocessValue(selectionRaw)
        val namespaceParts = deriveNamespace(editor.virtualFile.path)
        val suggestedKey = suggestionCache[cleanSelection] ?: nlpSuggestion(cleanSelection)

        val baseKeyInput = Messages.showInputDialog(
            project,
            "Enter the i18n key name:",
            "i18n Key",
            Messages.getQuestionIcon(),
            suggestedKey,
            null
        )?.trim() ?: return

        val finalBaseKey = if (hasHtml) "${baseKeyInput}_html" else baseKeyInput
        suggestionCache[cleanSelection] = finalBaseKey
        saveCache()

        val finalKey = addI18nKeyNested(
            "$projectBase/config/locales/vendor_admin.en.yml",
            namespaceParts,
            finalBaseKey,
            cleanSelection
        )

        WriteCommandAction.runWriteCommandAction(project) {
            val document = editor.document
            document.replaceString(
                editor.selectionModel.selectionStart,
                editor.selectionModel.selectionEnd,
                "t('vendor_admin.${namespaceParts.joinToString(".")}.$finalKey')"
            )
            editor.caretModel.moveToOffset(
                editor.selectionModel.selectionStart + finalKey.length
            )
            editor.selectionModel.removeSelection()
        }

        Messages.showInfoMessage(
            project,
            "Added i18n key: vendor_admin.${namespaceParts.joinToString(".")}.$finalKey",
            "i18n Extracted"
        )
    }

    private fun preprocessValue(value: String): Pair<String, Boolean> {
        val cleaned = value.replace("'", "").replace("\"", "").trim()
        val htmlRegex = Regex("<(\\w+)([^>]*)>|</(\\w+)>")
        val containsHtml = htmlRegex.containsMatchIn(value)
        return cleaned to containsHtml
    }

    private fun deriveNamespace(filePath: String): List<String> {
        val file = File(filePath)
        val relPath = try {
            file.relativeTo(File(projectBase)).path.replace(File.separatorChar, '/')
        } catch (_: Exception) {
            file.name
        }

        val parts = relPath.split('/')
        val viewsIndex = parts.indexOf("views")

        return when {
            viewsIndex >= 0 && viewsIndex + 1 < parts.size -> {
                val folders = parts.subList(viewsIndex + 1, parts.size - 1)
                val filename = parts.last().substringBefore(".")
                val finalName = if (filename in listOf("home", "index")) listOf() else listOf(filename)
                folders + finalName
            }
            else -> listOf(parts.last().substringBefore("."))
        }
    }

    private fun nlpSuggestion(value: String): String {
        val words = value.trim().split("\\s+".toRegex())
        return words.take(3)
            .map { it.trim('\'', '"').lowercase() }
            .joinToString("_")
    }

    private fun addI18nKeyNested(
        filePath: String,
        namespaceParts: List<String>,
        baseKey: String,
        value: String
    ): String {
        val yaml = Yaml()
        val file = File(filePath)
        file.parentFile.mkdirs()

        // ✅ Load safely into Map<String, Any>
        val data: MutableMap<String, Any> = try {
            if (file.exists()) {
                val loaded = yaml.load<Any>(FileReader(file))
                if (loaded is Map<*, *>) {
                    loaded as MutableMap<String, Any>
                } else {
                    mutableMapOf()
                }
            } else mutableMapOf()
        } catch (ex: Exception) {
            println("⚠️ Failed to parse YAML (${ex.message}), skipping write to avoid overwrite.")
            return baseKey
        }

        var currentMap = data.getOrPut("vendor_admin") { mutableMapOf<String, Any>() } as MutableMap<String, Any>
        for (part in namespaceParts) {
            currentMap = currentMap.getOrPut(part) { mutableMapOf<String, Any>() } as MutableMap<String, Any>
        }

        var key = baseKey
        var suffix = 1
        while (currentMap.containsKey(key) && currentMap[key] != value) {
            suffix++
            key = "${baseKey}_$suffix"
        }
        currentMap[key] = value

        val options = DumperOptions().apply {
            defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
            indent = 2
        }

        try {
            FileWriter(file).use { Yaml(options).dump(data, it) }
        } catch (ex: Exception) {
            println("❌ Failed to write YAML: ${ex.message}")
        }

        return key
    }

    private fun loadCache(): MutableMap<String, String> {
        val file = File(cachePath)
        return if (file.exists()) {
            try {
                val type = object : TypeToken<MutableMap<String, String>>() {}.type
                Gson().fromJson(FileReader(file), type) ?: mutableMapOf()
            } catch (_: Exception) {
                mutableMapOf()
            }
        } else mutableMapOf()
    }

    private fun saveCache() {
        try {
            FileWriter(cachePath).use { Gson().toJson(suggestionCache, it) }
        } catch (_: Exception) {
            println("Failed to save cache.")
        }
    }
}