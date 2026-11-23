package tech.fonia.lessons

/**
 * SplitMix64, bit for bit the generator of `:core:domain` and iOS (chapter 06 §2.5): `ULong` arithmetic wraps like
 * Swift's `&+`/`&*`, `shr` is logical, `%` unsigned. Lists of 0 or 1 items are returned without drawing. Copied
 * rather than shared because `:core:domain`'s is internal and this module must not depend on it.
 */
internal class SeededRandom(seed: ULong) {
    private var state: ULong = seed

    fun next(): ULong {
        state += 0x9E37_79B9_7F4A_7C15uL
        var mixed = state
        mixed = (mixed xor (mixed shr 30)) * 0xBF58_476D_1CE4_E5B9uL
        mixed = (mixed xor (mixed shr 27)) * 0x94D0_49BB_1331_11EBuL
        return mixed xor (mixed shr 31)
    }

    fun below(bound: Int): Int {
        require(bound > 0) { "bound must be above zero, got $bound" }
        return (next() % bound.toULong()).toInt()
    }

    /** Fisher-Yates from the tail: for each index from the last down to 1, swap it with `below(index + 1)`. */
    fun <Item> shuffled(items: List<Item>): List<Item> {
        val result = items.toMutableList()
        if (result.size <= 1) return result
        for (index in result.size - 1 downTo 1) {
            val other = below(index + 1)
            val held = result[index]
            result[index] = result[other]
            result[other] = held
        }
        return result
    }

    companion object {
        /** FNV-1a over UTF-8, for mixing a text into a seed. */
        fun hash(text: String): ULong =
            text.encodeToByteArray().fold(0xCBF2_9CE4_8422_2325uL) { hash, byte ->
                (hash xor byte.toUByte().toULong()) * 0x0000_0100_0000_01B3uL
            }
    }
}
