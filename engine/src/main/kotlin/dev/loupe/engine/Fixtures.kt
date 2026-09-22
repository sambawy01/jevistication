package dev.loupe.engine

import kotlin.random.Random

/**
 * One labelled fixture: an item, the answer a judgment should give, and the source group it came
 * from — the holiday the photos are from, the thread the emails are in, the statement the rows
 * came off.
 */
data class Fixture(val item: Item, val trueLabel: String, val sourceGroup: String)

/**
 * Fixtures divided into three disjoint splits.
 *
 * [calibration] is deliberately distinct from [test]: A6 fits on calibration and §9 forbids
 * fitting anything on test, because a threshold or temperature tuned on test reports a number that
 * cannot be trusted.
 */
data class FixtureSplit(
    val dev: List<Fixture>,
    val calibration: List<Fixture>,
    val test: List<Fixture>,
) {
    val size: Int get() = dev.size + calibration.size + test.size
}

object Fixtures {
    /**
     * Splits fixtures **by source group, never by item**.
     *
     * Two photos of the same receipt, or two emails in one thread, are not independent: splitting
     * them across train and test leaks the answer and reports an accuracy the model will not
     * reproduce on anything new. Whole groups therefore move together.
     *
     * Assignment is seeded, so the same corpus and seed always produce the same split.
     *
     * @throws IllegalArgumentException if the shares are not in (0,1) or leave no room for dev.
     */
    fun splitByGroup(
        fixtures: List<Fixture>,
        calibrationShare: Double = 0.2,
        testShare: Double = 0.2,
        seed: Long = 0L,
    ): FixtureSplit {
        require(calibrationShare > 0.0 && testShare > 0.0) { "shares must be positive" }
        require(calibrationShare + testShare < 1.0) {
            "calibration and test shares must leave room for dev, were " +
                "$calibrationShare + $testShare"
        }
        if (fixtures.isEmpty()) return FixtureSplit(emptyList(), emptyList(), emptyList())

        val groups = fixtures.groupBy { it.sourceGroup }
        val order = groups.keys.sorted().shuffled(Random(seed))

        val total = fixtures.size
        val testTarget = total * testShare
        val calibrationTarget = total * calibrationShare

        val test = mutableListOf<Fixture>()
        val calibration = mutableListOf<Fixture>()
        val dev = mutableListOf<Fixture>()

        for (group in order) {
            val members = groups.getValue(group)
            when {
                test.size < testTarget -> test += members
                calibration.size < calibrationTarget -> calibration += members
                else -> dev += members
            }
        }
        return FixtureSplit(dev = dev, calibration = calibration, test = test)
    }
}
