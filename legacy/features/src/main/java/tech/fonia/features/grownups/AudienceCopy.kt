package tech.fonia.features.grownups

import tech.fonia.domain.Rung
import tech.fonia.domain.Stage

/**
 * Lines that differ between a child, who practises with an adult beside them, and an adult who practises alone
 * (iOS `AudienceCopy`). An adult is never sent to "an adult", and is never spoken of as "the child".
 */
object AudienceCopy {
    fun notesTitle(isChild: Boolean): String = if (isChild) "Notițe pentru logoped" else "Notițe"

    fun addNoteLabel(isChild: Boolean): String = if (isChild) "Adaugă o notiță pentru logoped" else "Adaugă o notiță"

    fun exportTitle(isChild: Boolean): String = if (isChild) "Trimite fișierul la logoped" else "Trimite fișierul"

    /** ExportDocument leaves the name out unless asked (ProfileExport). */
    fun exportDetail(isChild: Boolean): String =
        "Încercările și notițele, fără înregistrări. Fișierul nu conține ${if (isChild) "numele copilului" else "numele tău"}."

    fun calibrationDetail(isChild: Boolean): String =
        (if (isChild) "Zece sunete, cu un adult lângă." else "Zece sunete, într-un loc liniștit.") +
            " Fiecare voce are S-ul și Ș-ul ei, iar telefonul învață doar din cele pe care spui că sunt bune."
}

/** iOS `AdultCards.stageName`: where a rung sits in the course, in the words Romanian speech therapists use. */
object StageNames {
    fun name(stage: Stage): String = when (stage) {
        Stage.Establish -> "Emitere"
        Stage.ConsolidateEarly, Stage.ConsolidateLate -> "Consolidare"
        Stage.Differentiate -> "Diferențiere"
        Stage.Automate, Stage.Generalise -> "Automatizare"
    }

    /** "Etapa: Consolidare.", under a sound in "Cum merge". */
    fun line(rung: Rung): String = "Etapa: ${name(rung.stage)}."
}
