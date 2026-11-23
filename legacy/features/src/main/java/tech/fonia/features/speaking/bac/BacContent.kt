package tech.fonia.features.speaking.bac

import android.content.res.AssetManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import tech.fonia.features.speaking.MarkedText
import tech.fonia.features.speaking.array
import tech.fonia.features.speaking.obj
import tech.fonia.features.speaking.optionalString
import tech.fonia.features.speaking.string

// The BAC oral simulation, Proba A (V6 in fonia-android-plan/03 §1.5, F7 in the H report): tickets written for Fonia
// in the form of the exam, in `assets/drafts/bac_oral.json`. A ticket has a text and three tasks: two about the text,
// then an argued opinion. Drafts until a teacher of Romanian has read them; never the official subjects.

data class BacTicket(
    val id: String,
    val number: Int,
    /** "text literar", "text nonliterar". */
    val kind: String,
    val title: String,
    /** With the key words the content suggests; the reader can change them while preparing. */
    val text: MarkedText,
    /** The three tasks, the last one the opinion. */
    val tasks: List<String>,
)

/** One level of the exam as the published descriptors put it. Shown as what the teachers listen for, never as a result. */
data class BacLevel(val name: String, val description: String)

data class BacContent(
    val version: String,
    val status: String,
    val approvedBy: String?,
    val notice: String,
    val preparationMinutes: Int,
    val levels: List<BacLevel>,
    val levelsSource: String,
    val tickets: List<BacTicket>,
) {
    val isDraft: Boolean get() = approvedBy.isNullOrBlank()

    fun ticket(id: String): BacTicket? = tickets.firstOrNull { it.id == id }

    /** A ticket at random, not the one drawn last time when there is a choice. */
    fun draw(lastID: String?, random: kotlin.random.Random = kotlin.random.Random.Default): BacTicket {
        val choices = tickets.filter { it.id != lastID }.ifEmpty { tickets }
        return choices[random.nextInt(choices.size)]
    }

    companion object {
        const val ASSET = "drafts/bac_oral.json"

        fun load(assets: AssetManager): BacContent? = runCatching {
            parse(assets.open(ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() })
        }.getOrNull()

        fun parse(json: String): BacContent {
            val root = Json.parseToJsonElement(json).jsonObject
            val exam = root.obj("exam")
            return BacContent(
                version = root.string("version"),
                status = root.string("status"),
                approvedBy = root.optionalString("approvedBy"),
                notice = root.string("notice"),
                preparationMinutes = exam["preparationMinutes"]!!.jsonPrimitive.int,
                levels = exam.array("levels").map { BacLevel(it.jsonObject.string("name"), it.jsonObject.string("description")) },
                levelsSource = exam.string("levelsSource"),
                tickets = root.array("tickets").map { node ->
                    val ticket = node.jsonObject
                    val tasks = ticket.array("tasks").map { it.jsonPrimitive.content }
                    require(tasks.size == 3) { "${ticket.string("id")} has ${tasks.size} tasks" }
                    BacTicket(
                        id = ticket.string("id"),
                        number = ticket["number"]!!.jsonPrimitive.int,
                        kind = ticket.string("kind"),
                        title = ticket.string("title"),
                        text = MarkedText.parse(ticket.string("text")),
                        tasks = tasks,
                    )
                },
            )
        }
    }
}
