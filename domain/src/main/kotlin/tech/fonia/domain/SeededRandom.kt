package tech.fonia.domain

/**
 * SplitMix64. The standard library's shuffle and hashing can change between releases or runs, and sessions and plants
 * have to come out the same from the same seed.
 *
 * Bit for bit the iOS generator: `UInt64` is `ULong` here, so `+`, `*` wrap like Swift's `&+` and `&*`, `shr` is a logical
 * shift and `%` is unsigned. Any extra or missing draw changes every session after it, so the call pattern is kept too
 * (a list of 0 or 1 items is returned without drawing).
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

    /** A value in `0 until bound`. `bound` must be above zero: Swift traps on anything else. */
    fun below(bound: Int): Int {
        require(bound > 0) { "bound must be above zero, got $bound" }
        return (next() % bound.toULong()).toInt()
    }

    fun unit(): Double = (next() shr 11).toDouble() / (1uL shl 53).toDouble()

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
