package tech.fonia.app.judge

/**
 * Fast head or full head (ml/README.md "Wiring in :app", plan 03 §5): the fast verdict stands when a gate decided or it
 * is final already; otherwise the phoneme model is asked once (ZIPA, ≤ 600 ms, null on timeout / no model / failure)
 * and its evidence re-decides; without evidence the fast verdict stands. Pure and generic, so it is JVM-tested with fakes.
 */
object HeadChoice {
    /** Why the result came out as it did (saved as `detail.zipa`). */
    enum class Path(val raw: String) { Gate("gate"), FastFinal("fastFinal"), Full("full"), NoEvidence("noEvidence"), NoModel("noModel") }

    suspend fun <R, P> choose(
        fast: R,
        gateOrFinal: Path?,
        phones: (suspend () -> P?)?,
        full: (P) -> R,
    ): Pair<R, Path> {
        if (gateOrFinal != null) return fast to gateOrFinal
        if (phones == null) return fast to Path.NoModel
        val evidence = phones() ?: return fast to Path.NoEvidence
        return full(evidence) to Path.Full
    }
}
