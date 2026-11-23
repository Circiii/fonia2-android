package tech.fonia.store

import java.util.UUID

data class WeeklySoundCount(
    /** `YYYY-MM-DD` of that week's Monday. */
    val weekStart: String,
    val sound: String,
    val attempts: Int,
)

/**
 * How the machine's verdicts line up with the adult's for one sound, as plain counts.
 * There is deliberately no threshold or judgement attached: the logoped reads the numbers.
 */
data class SoundAgreement(
    val sound: String,
    /** Attempts where both gave a verdict (good, almost or not yet). */
    val compared: Int,
    val agreed: Int,
    /** Attempts the adult judged but the machine said "unclear" or did not judge. */
    val machineAbstained: Int,
) {
    val agreementRate: Double?
        get() = if (compared == 0) null else agreed.toDouble() / compared.toDouble()
}

/** Attempts per verdict for one sound. The adult's verdict overrides the machine's where both exist. */
data class SoundOutcomeCounts(
    val sound: String,
    val good: Int,
    val almost: Int,
    val notYet: Int,
    val undecided: Int,
)

internal object Aggregates {
    /** The Monday of a stored `localDay`. */
    private const val WEEK_START = "date(localDay, 'weekday 0', '-6 days')"

    fun weeklyAttempts(db: Database, profileId: UUID): List<WeeklySoundCount> = db.fetchAll(
        """
        SELECT $WEEK_START AS weekStart, sound, COUNT(*) AS attempts
        FROM attempt
        WHERE profileId = ? AND sound IS NOT NULL
        GROUP BY weekStart, sound
        ORDER BY weekStart, sound
        """.trimIndent(),
        listOf(profileId),
    ) { row ->
        WeeklySoundCount(weekStart = row.string("weekStart"), sound = row.string("sound"), attempts = row.int("attempts"))
    }

    fun agreement(db: Database, profileId: UUID): List<SoundAgreement> = db.fetchAll(
        """
        SELECT sound,
               SUM(machineOutcome IN ('good', 'almost', 'notYet')) AS compared,
               SUM(machineOutcome = adultOutcome) AS agreed,
               SUM(machineOutcome IN ('unclear', 'notJudged')) AS machineAbstained
        FROM attempt
        WHERE profileId = ?
          AND sound IS NOT NULL
          AND adultOutcome IN ('good', 'almost', 'notYet')
        GROUP BY sound
        ORDER BY sound
        """.trimIndent(),
        listOf(profileId),
    ) { row ->
        SoundAgreement(
            sound = row.string("sound"),
            compared = row.int("compared"),
            agreed = row.int("agreed"),
            machineAbstained = row.int("machineAbstained"),
        )
    }

    fun soundOutcomes(db: Database, profileId: UUID): List<SoundOutcomeCounts> = db.fetchAll(
        """
        SELECT sound,
               SUM(verdict = 'good') AS good,
               SUM(verdict = 'almost') AS almost,
               SUM(verdict = 'notYet') AS notYet,
               SUM(verdict IN ('unclear', 'notJudged')) AS undecided
        FROM (
            SELECT sound, COALESCE(adultOutcome, machineOutcome) AS verdict
            FROM attempt
            WHERE profileId = ? AND sound IS NOT NULL
        )
        GROUP BY sound
        ORDER BY sound
        """.trimIndent(),
        listOf(profileId),
    ) { row ->
        SoundOutcomeCounts(
            sound = row.string("sound"),
            good = row.int("good"),
            almost = row.int("almost"),
            notYet = row.int("notYet"),
            undecided = row.int("undecided"),
        )
    }
}
