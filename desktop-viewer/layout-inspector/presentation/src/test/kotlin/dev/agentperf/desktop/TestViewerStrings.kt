package dev.agentperf.desktop

import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory

internal fun testViewerStrings(
    language: ViewerLanguage = ViewerLanguage.ENGLISH,
): ViewerStrings =
    ViewerStrings.fromTemplates(
        language = language,
        values =
            loadStringResources(
                if (language == ViewerLanguage.SIMPLIFIED_CHINESE) {
                    Path.of("src/main/composeResources/values-zh/strings.xml")
                } else {
                    Path.of("src/main/composeResources/values/strings.xml")
                },
            ),
    )

private fun loadStringResources(path: Path): Map<String, String> {
    val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(path.toFile())
    val strings = document.getElementsByTagName("string")
    return buildMap(strings.length) {
        for (index in 0 until strings.length) {
            val element = strings.item(index)
            put(
                element.attributes.getNamedItem("name").nodeValue,
                element.textContent
                    .replace("\\n", "\n")
                    .replace("\\t", "\t")
                    .replace("\\'", "'")
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\"),
            )
        }
    }
}
